package com.example.websocket.kafka;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

/**
 * Publishes chat message events to the correct Kafka topic based on room type:
 *
 *   room starts with "dm__"  →  chat-messages-dm     (2 partitions)
 *   any other room name      →  chat-messages-group  (3 partitions)
 *
 * The partition key is always the roomName, so all messages within a room
 * land on the same partition — guaranteeing order within that room.
 */
@Service
public class ChatMessageProducer {

    private static final Logger log = LoggerFactory.getLogger(ChatMessageProducer.class);

    private final KafkaTemplate<String, ChatMessageEvent> kafkaTemplate;

    @Value("${server.instance.id:server-1}")
    private String instanceId;

    public ChatMessageProducer(KafkaTemplate<String, ChatMessageEvent> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    /**
     * Sends a {@link ChatMessageEvent} to the appropriate Kafka topic.
     * Topic is chosen dynamically based on whether the room is a DM or group.
     *
     * @param event the chat message event (originServerId will be set here)
     */
    public void send(ChatMessageEvent event) {
        event.setOriginServerId(instanceId);

        // Route to correct topic based on room type
        String topic = KafkaConfig.topicForRoom(event.getRoomName());

        CompletableFuture<SendResult<String, ChatMessageEvent>> future =
                kafkaTemplate.send(topic, event.getRoomName(), event);

        future.whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("[Kafka] Failed to send message from {} to room {} (topic={}): {}",
                        event.getSender(), event.getRoomName(), topic, ex.getMessage());
            } else {
                log.debug("[Kafka] Sent → topic={} partition={} offset={} room={} sender={}",
                        topic,
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset(),
                        event.getRoomName(),
                        event.getSender());
            }
        });
    }
}
