package com.example.websocket.config;

import com.example.websocket.kafka.ChatMessageEvent;
import com.example.websocket.kafka.ChatMessageProducer;
import com.example.websocket.model.Message;
import com.example.websocket.model.User;
import com.example.websocket.repo.MessageRepository;
import com.example.websocket.repo.UserRepository;
import com.example.websocket.service.UserProfileService;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

@Component
public class SocketConnectionHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(SocketConnectionHandler.class);

    /** Key: "username::roomName"  →  the one active session per user per room */
    private static final Map<String, WebSocketSession> userRoomSessions = new ConcurrentHashMap<>();

    /** Key: roomName  →  all sessions in that room */
    private static final Map<String, Set<WebSocketSession>> roomSessions = new ConcurrentHashMap<>();

    /**
     * All sessions for a given username across ALL rooms.
     * Used to push personal events (notifications, unread counts) without
     * knowing which room the user is currently viewing.
     */
    private static final Map<String, Set<WebSocketSession>> userSessions = new ConcurrentHashMap<>();

    private final ChatMessageProducer chatMessageProducer;
    private final UserProfileService userProfileService;
    private final MessageRepository messageRepository;
    private final UserRepository userRepository;

    public SocketConnectionHandler(ChatMessageProducer chatMessageProducer,
                                    UserProfileService userProfileService,
                                    MessageRepository messageRepository,
                                    UserRepository userRepository) {
        this.chatMessageProducer = chatMessageProducer;
        this.userProfileService = userProfileService;
        this.messageRepository = messageRepository;
        this.userRepository = userRepository;
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String username = (String) session.getAttributes().get("username");
        String roomName = (String) session.getAttributes().get("roomName");
        if (username == null || roomName == null) return;

        String key = sessionKey(username, roomName);
        WebSocketSession existing = userRoomSessions.put(key, session);
        if (existing != null && existing.isOpen()) {
            removeFromRoom(existing, roomName);
            removeFromUser(existing, username);
            try { existing.close(); } catch (Exception ignored) {}
        }
        roomSessions.computeIfAbsent(roomName, k -> new CopyOnWriteArraySet<>()).add(session);
        userSessions.computeIfAbsent(username, k -> new CopyOnWriteArraySet<>()).add(session);

        // Publish presence — respect manual status override
        try {
            userProfileService.setOnline(username);  // no-op if manualStatusOverride=true
            // Read the user's actual status from DB (may be AWAY/DND if manually set)
            User dbUser = userRepository.findByUsername(username);
            String actualStatus = (dbUser != null && dbUser.getStatus() != null)
                    ? dbUser.getStatus().name() : "ONLINE";

            ChatMessageEvent presenceEvent = new ChatMessageEvent();
            presenceEvent.setEventType(ChatMessageEvent.EventType.PRESENCE);
            presenceEvent.setSender(username);
            presenceEvent.setRoomName(roomName);
            presenceEvent.setPresenceStatus(actualStatus);
            presenceEvent.setTimestamp(LocalDateTime.now());
            chatMessageProducer.send(presenceEvent);
        } catch (Exception e) {
            log.warn("[WS] Failed to publish presence for {}: {}", username, e.getMessage());
        }
    }

    @Override
    protected void handleTextMessage(@Nullable WebSocketSession session,
                                     @Nullable TextMessage message) {
        try {
            assert message != null;
            assert session != null;
            JSONObject json = new JSONObject(message.getPayload());

            String sender   = (String) session.getAttributes().get("username");
            String roomName = json.optString("room", null);

            if (sender == null || sender.isBlank()) return;
            if (roomName == null || roomName.isBlank()) return;

            String sessionRoom = (String) session.getAttributes().get("roomName");
            if (!roomName.equals(sessionRoom)) {
                log.warn("Security: {} tried to post to {} but connected to {}", sender, roomName, sessionRoom);
                return;
            }

            String eventTypeStr = json.optString("eventType", "MESSAGE");
            ChatMessageEvent.EventType eventType;
            try { eventType = ChatMessageEvent.EventType.valueOf(eventTypeStr.toUpperCase()); }
            catch (Exception e) { eventType = ChatMessageEvent.EventType.MESSAGE; }

            ChatMessageEvent event = new ChatMessageEvent();
            event.setEventType(eventType);
            event.setSender(sender);
            event.setRoomName(roomName);
            event.setTimestamp(LocalDateTime.now());

            switch (eventType) {
                case TYPING:
                    event.setIsTyping(json.optBoolean("isTyping", true));
                    chatMessageProducer.send(event);
                    break;
                case READ_RECEIPT:
                    event.setMessageId(json.has("messageId") ? json.getLong("messageId") : null);
                    chatMessageProducer.send(event);
                    break;
                case MESSAGE:
                default:
                    event.setContent(json.optString("message", null));
                    event.setFileUrl(json.optString("fileUrl", null));
                    event.setFileType(json.optString("fileType", null));
                    event.setFileName(json.optString("fileName", null));
                    if (json.has("replyToMessageId")) event.setReplyToMessageId(json.getLong("replyToMessageId"));

                    chatMessageProducer.send(event);
                    break;
            }
        } catch (Exception e) {
            log.error("handleTextMessage error: {}", e.getMessage());
        }
    }

    // ── Broadcast helpers ──────────────────────────────────────────────────

    /**
     * Fans out a pre-built JSON payload to all sessions in a given room.
     * Used by REST controllers to push real-time updates (edit, delete, react, pin).
     */
    public void broadcastJsonToRoom(String roomName, JSONObject payload) {
        Set<WebSocketSession> targets = roomSessions.get(roomName);
        if (targets == null || targets.isEmpty()) return;
        TextMessage outgoing = new TextMessage(payload.toString());
        for (WebSocketSession s : targets) {
            if (s.isOpen()) {
                try { s.sendMessage(outgoing); }
                catch (Exception e) { log.error("[WS] broadcastJsonToRoom send failed {}: {}", s.getId(), e.getMessage()); }
            }
        }
    }

    /**
     * Fans out to all WebSocket sessions in a given room (Kafka-driven broadcast).
     */
    public void broadcastToLocalSessions(ChatMessageEvent event) {
        JSONObject broadcast = buildBroadcastJson(event);
        TextMessage outgoing = new TextMessage(broadcast.toString());
        Set<WebSocketSession> targets = roomSessions.get(event.getRoomName());
        if (targets == null || targets.isEmpty()) return;
        for (WebSocketSession s : targets) {
            if (s.isOpen()) {
                try { s.sendMessage(outgoing); }
                catch (Exception e) { log.error("[WS] Send failed {}: {}", s.getId(), e.getMessage()); }
            }
        }
    }

    /**
     * Pushes a personal event (NOTIFICATION, UNREAD_COUNT) directly to all
     * sessions belonging to a specific user — regardless of which room they are in.
     */
    public void pushToUser(String username, JSONObject payload) {
        Set<WebSocketSession> sessions = userSessions.get(username);
        if (sessions == null || sessions.isEmpty()) return;
        TextMessage msg = new TextMessage(payload.toString());
        for (WebSocketSession s : sessions) {
            if (s.isOpen()) {
                try { s.sendMessage(msg); }
                catch (Exception e) { log.error("[WS] pushToUser {} failed: {}", username, e.getMessage()); }
            }
        }
    }

    /**
     * Pushes a PRESENCE update to every room that currently has sessions.
     * This ensures users in OTHER rooms also see the presence dot change.
     */
    public void broadcastPresenceGlobally(String username, String status) {
        JSONObject payload = new JSONObject();
        payload.put("eventType",      "PRESENCE");
        payload.put("sender",         username);
        payload.put("presenceStatus", status);
        payload.put("timestamp",      LocalDateTime.now().toString());
        TextMessage msg = new TextMessage(payload.toString());

        // Broadcast to all open sessions across all rooms
        userRoomSessions.values().forEach(s -> {
            if (s.isOpen()) {
                try { s.sendMessage(msg); }
                catch (Exception ignored) {}
            }
        });
    }

    private JSONObject buildBroadcastJson(ChatMessageEvent event) {
        JSONObject j = new JSONObject();
        j.put("eventType", event.getEventType() != null ? event.getEventType().name() : "MESSAGE");
        j.put("sender",    event.getSender());
        j.put("roomName",  event.getRoomName());
        j.put("timestamp", event.getTimestamp() != null ? event.getTimestamp().toString() : "");

        switch (event.getEventType() != null ? event.getEventType() : ChatMessageEvent.EventType.MESSAGE) {
            case TYPING:
                j.put("isTyping", Boolean.TRUE.equals(event.getIsTyping()));
                break;
            case PRESENCE:
                j.put("presenceStatus", event.getPresenceStatus());
                break;
            case REACTION:
                j.put("messageId",     event.getMessageId());
                j.put("reactionEmoji", event.getReactionEmoji());
                break;
            case MESSAGE_EDIT:
                j.put("messageId", event.getMessageId());
                j.put("content",   event.getContent());
                break;
            case MESSAGE_DELETE:
                j.put("messageId", event.getMessageId());
                break;
            case PIN:
                j.put("messageId", event.getMessageId());
                break;
            case NOTIFICATION:
                j.put("notificationId",    event.getNotificationId());
                j.put("content",           event.getContent());
                j.put("recipientUsername", event.getRecipientUsername());
                break;
            case UNREAD_COUNT:
                j.put("roomName",     event.getRoomName());
                j.put("unreadCount",  event.getContent()); // piggyback count as string
                break;
            default:
                j.put("message",  event.getContent());
                j.put("fileUrl",  event.getFileUrl());
                j.put("fileType", event.getFileType());
                j.put("fileName", event.getFileName());
                if (event.getMessageId() != null) j.put("id", event.getMessageId());
                // Include full reply-to object for live message display
                if (event.getReplyToMessageId() != null) {
                    j.put("replyToMessageId", event.getReplyToMessageId());
                    try {
                        Message replyMsg = messageRepository.findById(event.getReplyToMessageId()).orElse(null);
                        if (replyMsg != null && !replyMsg.isDeleted()) {
                            JSONObject replyTo = new JSONObject();
                            replyTo.put("id",      replyMsg.getId());
                            replyTo.put("sender",  replyMsg.getSender());
                            replyTo.put("content", replyMsg.getContent() != null ? replyMsg.getContent() : "");
                            j.put("replyTo", replyTo);
                        }
                    } catch (Exception e) {
                        log.warn("[WS] Failed to load reply-to message {}: {}", event.getReplyToMessageId(), e.getMessage());
                    }
                }
                break;
        }
        return j;
    }

    // ── Connection closed ──────────────────────────────────────────────────

    @Override
    public void afterConnectionClosed(WebSocketSession session,
                                      @Nullable CloseStatus status) {
        String username = (String) session.getAttributes().get("username");
        String roomName = (String) session.getAttributes().get("roomName");
        if (username == null || roomName == null) return;

        userRoomSessions.remove(sessionKey(username, roomName), session);
        removeFromRoom(session, roomName);
        removeFromUser(session, username);

        boolean hasOtherSessions = userRoomSessions.keySet().stream()
                .anyMatch(k -> k.startsWith(username + "::") && userRoomSessions.get(k) != null
                               && userRoomSessions.get(k).isOpen());
        if (!hasOtherSessions) {
            try {
                userProfileService.setOffline(username);  // no-op if manualStatusOverride=true
                // Read the user's actual status from DB (may still be AWAY/DND if manually set)
                User dbUser = userRepository.findByUsername(username);
                String actualStatus = (dbUser != null && dbUser.getStatus() != null)
                        ? dbUser.getStatus().name() : "OFFLINE";
                broadcastPresenceGlobally(username, actualStatus);
            } catch (Exception e) {
                log.warn("[WS] Failed to publish offline presence for {}: {}", username, e.getMessage());
            }
        }
    }

    // ── Private helpers ────────────────────────────────────────────────────

    private static String sessionKey(String u, String r) { return u + "::" + r; }

    private void removeFromRoom(WebSocketSession session, String roomName) {
        Set<WebSocketSession> s = roomSessions.get(roomName);
        if (s != null) { s.remove(session); if (s.isEmpty()) roomSessions.remove(roomName); }
    }

    private void removeFromUser(WebSocketSession session, String username) {
        Set<WebSocketSession> s = userSessions.get(username);
        if (s != null) { s.remove(session); if (s.isEmpty()) userSessions.remove(username); }
    }
}
