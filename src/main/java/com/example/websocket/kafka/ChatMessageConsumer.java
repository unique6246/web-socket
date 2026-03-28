package com.example.websocket.kafka;

import com.example.websocket.config.SocketConnectionHandler;
import com.example.websocket.service.ChatRoomService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

/**
 * Kafka consumers for both chat topics:
 *   - chat-messages-dm     (direct messages)
 *   - chat-messages-group  (group / channel messages)
 * <p>
 * Three separate listener methods cover the three downstream concerns:
 * <p>
 *  1. ws-broadcast      — fan-out to WebSocket sessions on THIS server instance.
 *                         Every server instance has a unique group ID, so every
 *                         instance receives and broadcasts every message.
 * <p>
 *  2. chat-persistence  — persist the message to the database exactly once across
 *                         the whole cluster (shared consumer group).
 * <p>
 *  3. push-notifications — stub for push notification service (shared group).
 *                          Extend to call FCM / APNs / email etc.
 */
@Service
public class ChatMessageConsumer {

    private static final Logger log = LoggerFactory.getLogger(ChatMessageConsumer.class);

    private final SocketConnectionHandler socketConnectionHandler;
    private final ChatRoomService chatRoomService;

    public ChatMessageConsumer(SocketConnectionHandler socketConnectionHandler,
                               ChatRoomService chatRoomService) {
        this.socketConnectionHandler = socketConnectionHandler;
        this.chatRoomService = chatRoomService;
    }

    // ── 1. WebSocket Fan-out ───────────────────────────────────────────────

    /**
     * Listens on BOTH topics (DM + Group).
     * Each server instance subscribes with its own unique group ID so ALL instances
     * receive and broadcast every message to their locally-connected WebSocket sessions.
     */
    @KafkaListener(
            topics = {KafkaConfig.TOPIC_DM, KafkaConfig.TOPIC_GROUP},
            containerFactory = "wsBroadcastContainerFactory"
    )
    public void onMessageBroadcast(ChatMessageEvent event) {
        log.debug("[Kafka→WS] Broadcasting message from {} in room {} (origin: {})",
                event.getSender(), event.getRoomName(), event.getOriginServerId());
        socketConnectionHandler.broadcastToLocalSessions(event);
    }

    // ── 2. Database Persistence ───────────────────────────────────────────

    /**
     * Shared consumer group "chat-persistence" — only one instance in the cluster
     * will receive each message, preventing duplicate DB writes.
     * Listens on both DM and Group topics.
     */
    @KafkaListener(
            topics = {KafkaConfig.TOPIC_DM, KafkaConfig.TOPIC_GROUP},
            containerFactory = "chatPersistenceContainerFactory"
    )
    public void onMessagePersist(ChatMessageEvent event) {
        log.debug("[Kafka→DB] Persisting message from {} in room {}", event.getSender(), event.getRoomName());
        try {
            chatRoomService.saveMessage(
                    event.getRoomName(),
                    event.getSender(),
                    event.getContent(),
                    event.getFileUrl(),
                    event.getFileType(),
                    event.getFileName()
            );
        } catch (Exception e) {
            log.error("[Kafka→DB] Failed to persist message from {} in room {}: {}",
                    event.getSender(), event.getRoomName(), e.getMessage());
        }
    }

    // ── 3. Push Notifications ─────────────────────────────────────────────

    /**
     * Shared consumer group "push-notifications".
     * Extend this to send FCM/APNs/email push notifications to offline users.
     * Listens on both DM and Group topics.
     */
    @KafkaListener(
            topics = {KafkaConfig.TOPIC_DM, KafkaConfig.TOPIC_GROUP},
            containerFactory = "pushNotificationContainerFactory"
    )
    public void onMessagePushNotify(ChatMessageEvent event) {
        log.debug("[Kafka→Push] Push notification stub — sender={} room={}", event.getSender(), event.getRoomName());
        // TODO: Integrate with FCM / APNs / email notification service here.
        // Example:
        //   pushNotificationService.notifyOfflineUsers(event.getRoomName(), event.getSender(), event.getContent());
    }
}
