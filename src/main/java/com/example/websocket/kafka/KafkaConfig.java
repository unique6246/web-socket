package com.example.websocket.kafka;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.*;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.HashMap;
import java.util.Map;

/**
 * Kafka producer/consumer/topic configuration.
 *
 * ── Topic Design (real-world scale) ──────────────────────────────────────────
 *
 *  Instead of one topic with few partitions (which causes hot-partition problems
 *  at thousands of rooms), we use TWO topics split by room type:
 *
 *  chat-messages-dm      → all direct-message (DM) rooms   (2 partitions)
 *  chat-messages-group   → all group/channel rooms         (3 partitions)
 *
 *  Why two topics?
 *  - DMs are private (2 people). Isolated from groups.
 *  - Groups are broadcast-heavy (many recipients).
 *  - Separating them prevents a busy group from blocking DM delivery.
 *
 *  Partition key = roomName (consistent hashing → same room always hits same partition)
 *  This guarantees message ORDER within a room regardless of concurrent senders.
 *
 * ── Consumer Groups ───────────────────────────────────────────────────────────
 *  - "ws-broadcast-{instanceId}" → unique per server, every instance gets every message (fan-out)
 *  - "chat-persistence"          → shared, DB write happens exactly once across cluster
 *  - "push-notifications"        → shared, push notification sent exactly once
 */
@Configuration
public class KafkaConfig {

    // ── DM topic: direct messages between two users ───────────────────────
    public static final String TOPIC_DM            = "chat-messages-dm";
    public static final int    TOPIC_DM_PARTITIONS = 2;

    // ── Group topic: group chats / channels ───────────────────────────────
    public static final String TOPIC_GROUP            = "chat-messages-group";
    public static final int    TOPIC_GROUP_PARTITIONS = 3;

    // ── Replication factor (set to 3 in production multi-broker clusters) ─
    public static final short  TOPIC_REPLICAS = 1;

    /**
     * Routes a room name to the correct Kafka topic.
     * DM rooms are prefixed with "dm__" (e.g. "dm__alice__bob").
     * Everything else is treated as a group/channel.
     */
    public static String topicForRoom(String roomName) {
        return (roomName != null && roomName.startsWith("dm__"))
                ? TOPIC_DM
                : TOPIC_GROUP;
    }

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    // ── Topics ────────────────────────────────────────────────────────────

    @Bean
    public NewTopic dmTopic() {
        return TopicBuilder.name(TOPIC_DM)
                .partitions(TOPIC_DM_PARTITIONS)
                .replicas(TOPIC_REPLICAS)
                .build();
    }

    @Bean
    public NewTopic groupTopic() {
        return TopicBuilder.name(TOPIC_GROUP)
                .partitions(TOPIC_GROUP_PARTITIONS)
                .replicas(TOPIC_REPLICAS)
                .build();
    }

    // ── Producer ──────────────────────────────────────────────────────────

    @Bean
    public ProducerFactory<String, ChatMessageEvent> producerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,   StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        // Strongest durability: wait for all in-sync replicas to ack
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        // Idempotent producer prevents duplicate messages on retry
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        // REQUIRED when idempotence=true: max in-flight requests per connection ≤ 5
        props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        props.put(ProducerConfig.RETRIES_CONFIG, 3);
        // Micro-batch: wait up to 5 ms to accumulate messages before sending —
        // reduces per-message overhead without noticeable latency impact
        props.put(ProducerConfig.LINGER_MS_CONFIG, 5);
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, 16384); // 16 KB
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    public KafkaTemplate<String, ChatMessageEvent> kafkaTemplate() {
        return new KafkaTemplate<>(producerFactory());
    }

    // ── Shared consumer config helper ─────────────────────────────────────

    private Map<String, Object> baseConsumerProps(String groupId) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,   StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(JsonDeserializer.TRUSTED_PACKAGES, "com.example.websocket.kafka");
        props.put(JsonDeserializer.VALUE_DEFAULT_TYPE, ChatMessageEvent.class.getName());
        return props;
    }

    private ConcurrentKafkaListenerContainerFactory<String, ChatMessageEvent>
    containerFactory(String groupId, int concurrency) {
        ConsumerFactory<String, ChatMessageEvent> cf =
                new DefaultKafkaConsumerFactory<>(baseConsumerProps(groupId));
        ConcurrentKafkaListenerContainerFactory<String, ChatMessageEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(cf);
        // Each consumer thread handles one partition — set to match partition count
        factory.setConcurrency(concurrency);
        return factory;
    }

    /**
     * Fan-out container factory — unique group per instance.
     * Concurrency = max(DM_partitions, GROUP_partitions) = 3 so this instance
     * can drain all partitions of both topics in parallel.
     */
    @Bean("wsBroadcastContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, ChatMessageEvent>
    wsBroadcastContainerFactory(
            @Value("${kafka.ws-broadcast.group-id:ws-broadcast-default}") String groupId) {
        return containerFactory(groupId, Math.max(TOPIC_DM_PARTITIONS, TOPIC_GROUP_PARTITIONS));
    }

    /**
     * Persistence container factory — shared group, exactly-once DB write.
     * Concurrency = total partitions across both topics (2+3=5).
     */
    @Bean("chatPersistenceContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, ChatMessageEvent>
    chatPersistenceContainerFactory() {
        return containerFactory("chat-persistence", TOPIC_DM_PARTITIONS + TOPIC_GROUP_PARTITIONS);
    }

    /**
     * Push-notification container factory — shared group, exactly-once push.
     * Concurrency = total partitions across both topics (2+3=5).
     */
    @Bean("pushNotificationContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, ChatMessageEvent>
    pushNotificationContainerFactory() {
        return containerFactory("push-notifications", TOPIC_DM_PARTITIONS + TOPIC_GROUP_PARTITIONS);
    }
}
