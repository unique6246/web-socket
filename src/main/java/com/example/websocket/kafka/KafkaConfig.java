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
        props.put(ProducerConfig.ACKS_CONFIG, "all");           // strongest durability
        props.put(ProducerConfig.RETRIES_CONFIG, 3);
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
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
    containerFactory(String groupId) {
        ConsumerFactory<String, ChatMessageEvent> cf =
                new DefaultKafkaConsumerFactory<>(baseConsumerProps(groupId));
        ConcurrentKafkaListenerContainerFactory<String, ChatMessageEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(cf);
        return factory;
    }

    /**
     * Fan-out container factory.
     * Each server supplies its own unique group ID so every instance gets every message.
     * The actual group ID is resolved at runtime via ${kafka.ws-broadcast.group-id}.
     */
    @Bean("wsBroadcastContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, ChatMessageEvent>
    wsBroadcastContainerFactory(
            @Value("${kafka.ws-broadcast.group-id:ws-broadcast-default}") String groupId) {
        return containerFactory(groupId);
    }

    /**
     * Persistence container factory.
     * Shared group across all instances → DB write happens exactly once.
     */
    @Bean("chatPersistenceContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, ChatMessageEvent>
    chatPersistenceContainerFactory() {
        return containerFactory("chat-persistence");
    }

    /**
     * Push-notification container factory.
     * Shared group → notification is sent exactly once per message.
     */
    @Bean("pushNotificationContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, ChatMessageEvent>
    pushNotificationContainerFactory() {
        return containerFactory("push-notifications");
    }
}
