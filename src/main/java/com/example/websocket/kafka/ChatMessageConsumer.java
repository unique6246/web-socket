package com.example.websocket.kafka;

import com.example.websocket.config.SocketConnectionHandler;
import com.example.websocket.model.Message;
import com.example.websocket.model.NotificationType;
import com.example.websocket.model.User;
import com.example.websocket.repo.UserRepository;
import com.example.websocket.service.ChatRoomService;
import com.example.websocket.service.MessageService;
import com.example.websocket.service.NotificationService;
import com.example.websocket.service.ReactionService;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ChatMessageConsumer {

    private static final Logger log = LoggerFactory.getLogger(ChatMessageConsumer.class);
    private static final Pattern MENTION_PATTERN = Pattern.compile("@(\\w[\\w.-]{1,49})");

    private final SocketConnectionHandler socketConnectionHandler;
    private final ChatRoomService chatRoomService;
    private final MessageService messageService;
    private final ReactionService reactionService;
    private final NotificationService notificationService;
    private final UserRepository userRepository;

    public ChatMessageConsumer(SocketConnectionHandler socketConnectionHandler,
                               ChatRoomService chatRoomService,
                               MessageService messageService,
                               ReactionService reactionService,
                               NotificationService notificationService,
                               UserRepository userRepository) {
        this.socketConnectionHandler = socketConnectionHandler;
        this.chatRoomService = chatRoomService;
        this.messageService = messageService;
        this.reactionService = reactionService;
        this.notificationService = notificationService;
        this.userRepository = userRepository;
    }

    // 1. WebSocket Fan-out
    @KafkaListener(
            topics = {KafkaConfig.TOPIC_DM, KafkaConfig.TOPIC_GROUP},
            containerFactory = "wsBroadcastContainerFactory"
    )
    public void onMessageBroadcast(ChatMessageEvent event) {
        log.debug("[Kafka->WS] Broadcasting {} from {} in room {}", event.getEventType(), event.getSender(), event.getRoomName());
        socketConnectionHandler.broadcastToLocalSessions(event);
        if (event.getEventType() == ChatMessageEvent.EventType.PRESENCE) {
            socketConnectionHandler.broadcastPresenceGlobally(event.getSender(), event.getPresenceStatus());
        }
    }

    // 2. Database Persistence
    @KafkaListener(
            topics = {KafkaConfig.TOPIC_DM, KafkaConfig.TOPIC_GROUP},
            containerFactory = "chatPersistenceContainerFactory"
    )
    public void onMessagePersist(ChatMessageEvent event) {
        if (event.getEventType() == null) return;
        try {
            switch (event.getEventType()) {
                case MESSAGE:
                    log.debug("[Kafka->DB] Persisting message from {} in room {}", event.getSender(), event.getRoomName());
                    Message saved = chatRoomService.saveMessage(
                            event.getRoomName(), event.getSender(),
                            event.getContent(), event.getFileUrl(),
                            event.getFileType(), event.getFileName(),
                            event.getReplyToMessageId());

                    // Push the DB-assigned ID back to the room so the frontend
                    // can patch the bubble's data-id.  This enables reply,
                    // react, edit, delete on freshly sent messages without
                    // a page refresh.
                    if (saved != null && saved.getId() != null) {
                        try {
                            JSONObject idAssign = new JSONObject();
                            idAssign.put("eventType",  "MESSAGE_ID_ASSIGN");
                            idAssign.put("sender",     event.getSender());
                            idAssign.put("roomName",   event.getRoomName());
                            idAssign.put("messageId",  saved.getId());
                            idAssign.put("content",    event.getContent());
                            idAssign.put("timestamp",  event.getTimestamp() != null
                                    ? event.getTimestamp().toString() : "");
                            socketConnectionHandler.broadcastJsonToRoom(
                                    event.getRoomName(), idAssign);
                        } catch (Exception e) {
                            log.warn("[Consumer] Failed to push MESSAGE_ID_ASSIGN: {}",
                                    e.getMessage());
                        }
                    }

                    pushUnreadCountToRoomMembers(event.getRoomName(), event.getSender());

                    // ── Create notifications for DM messages ──────────────────
                    try {
                        if (event.getRoomName() != null && event.getRoomName().startsWith("dm__")) {
                            String sender = event.getSender();
                            // Extract the other user from room name "dm__userA__userB"
                            String stripped = event.getRoomName().substring(4); // remove "dm__"
                            String[] parts = stripped.split("__");
                            for (String part : parts) {
                                if (!part.equals(sender)) {
                                    User recipient = userRepository.findByUsername(part);
                                    if (recipient != null) {
                                        notificationService.createNotification(
                                            recipient, NotificationType.MESSAGE, null,
                                            sender + " sent you a message");
                                    }
                                }
                            }
                        }
                    } catch (Exception e) {
                        log.warn("[Consumer] Failed to create DM notification: {}", e.getMessage());
                    }

                    // ── Create notifications for @mentions ────────────────────
                    try {
                        if (event.getContent() != null && event.getContent().contains("@")) {
                            String sender = event.getSender();
                            Set<String> mentioned = new HashSet<>();
                            Matcher matcher = MENTION_PATTERN.matcher(event.getContent());
                            while (matcher.find()) {
                                String mentionedName = matcher.group(1);
                                if (!mentionedName.equals(sender) && mentioned.add(mentionedName)) {
                                    User recipient = userRepository.findByUsername(mentionedName);
                                    if (recipient != null) {
                                        notificationService.createNotification(
                                            recipient, NotificationType.MENTION, null,
                                            sender + " mentioned you in " + event.getRoomName());
                                    }
                                }
                            }
                        }
                    } catch (Exception e) {
                        log.warn("[Consumer] Failed to create mention notification: {}", e.getMessage());
                    }

                    break;
                case MESSAGE_EDIT:
                    if (event.getMessageId() != null && event.getContent() != null)
                        messageService.editMessage(event.getMessageId(), event.getSender(), event.getContent());
                    break;
                case MESSAGE_DELETE:
                    if (event.getMessageId() != null)
                        messageService.deleteMessage(event.getMessageId(), event.getSender());
                    break;
                case REACTION:
                    if (event.getMessageId() != null && event.getReactionEmoji() != null)
                        reactionService.addReaction(event.getMessageId(), event.getSender(), event.getReactionEmoji());
                    break;
                case PIN:
                    if (event.getMessageId() != null)
                        messageService.pinMessage(event.getMessageId(), event.getSender());
                    break;
                case TYPING:
                case READ_RECEIPT:
                case PRESENCE:
                case NOTIFICATION:
                case UNREAD_COUNT:
                default:
                    break;
            }
        } catch (Exception e) {
            log.error("[Kafka->DB] Failed to persist event {} from {}: {}", event.getEventType(), event.getSender(), e.getMessage());
        }
    }

    // 3. Push Notifications (stub)
    @KafkaListener(
            topics = {KafkaConfig.TOPIC_DM, KafkaConfig.TOPIC_GROUP},
            containerFactory = "pushNotificationContainerFactory"
    )
    public void onMessagePushNotify(ChatMessageEvent event) {
        if (event.getEventType() != ChatMessageEvent.EventType.MESSAGE) return;
        log.debug("[Kafka->Push] Push notification stub - sender={} room={}", event.getSender(), event.getRoomName());
    }

    // Helpers
    private void pushUnreadCountToRoomMembers(String roomName, String sender) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("eventType", "UNREAD_COUNT");
            payload.put("roomName",  roomName);
            payload.put("increment", 1);
            chatRoomService.getMemberUsernames(roomName).forEach(username -> {
                if (!username.equals(sender)) {
                    socketConnectionHandler.pushToUser(username, payload);
                }
            });
        } catch (Exception e) {
            log.warn("[Consumer] Failed to push unread count for room {}: {}", roomName, e.getMessage());
        }
    }
}
