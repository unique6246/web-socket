package com.example.websocket.config;

import com.example.websocket.service.ChatRoomService;
import org.json.JSONObject;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

@Component
public class SocketConnectionHandler extends TextWebSocketHandler {

    /**
     * Keyed by "username::roomName"  →  session
     * Allows one active session per user per room (DM or Group).
     */
    private static final Map<String, WebSocketSession> userRoomSessions = new ConcurrentHashMap<>();

    /** roomName  →  set of open WebSocket sessions (all users in that room) */
    private static final Map<String, Set<WebSocketSession>> roomSessions = new ConcurrentHashMap<>();

    private final ChatRoomService chatRoomService;

    public SocketConnectionHandler(ChatRoomService chatRoomService) {
        this.chatRoomService = chatRoomService;
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String username = (String) session.getAttributes().get("username");
        String roomName = (String) session.getAttributes().get("roomName");
        if (username == null || roomName == null) return;

        String key = sessionKey(username, roomName);

        // If there is already a session for this user+room, close it gracefully
        WebSocketSession existing = userRoomSessions.put(key, session);
        if (existing != null && existing.isOpen()) {
            removeFromRoom(existing, roomName);
            try { existing.close(); } catch (Exception ignored) {}
        }

        // Register in room map
        roomSessions.computeIfAbsent(roomName, k -> new CopyOnWriteArraySet<>()).add(session);
    }

    @Override
    protected void handleTextMessage(@Nullable WebSocketSession session,
                                     @Nullable TextMessage message) {
        try {
            assert message != null;
            assert session != null;
            JSONObject json = new JSONObject(message.getPayload());

            // SECURITY: Use server-side authenticated username from handshake — never trust client-supplied sender
            String sender    = (String) session.getAttributes().get("username");
            String roomName  = json.optString("room",     null);
            String content   = json.optString("message",  null);
            String fileUrl   = json.optString("fileUrl",  null);
            String fileType  = json.optString("fileType", null);
            String fileName  = json.optString("fileName", null);

            if (sender == null || sender.isBlank()) return;
            if (roomName == null || roomName.isBlank()) return;

            // Verify sender is actually in this room (access control)
            String sessionRoom = (String) session.getAttributes().get("roomName");
            if (!roomName.equals(sessionRoom)) {
                System.err.println("Security: User " + sender + " tried to post to " + roomName + " but is connected to " + sessionRoom);
                return;
            }

            // Persist
            chatRoomService.saveMessage(roomName, sender, content, fileUrl, fileType, fileName);

            // Broadcast to every session currently in this room
            JSONObject broadcast = new JSONObject();
            broadcast.put("sender",   sender);
            broadcast.put("message",  content);
            broadcast.put("roomName", roomName);
            broadcast.put("fileUrl",  fileUrl);
            broadcast.put("fileType", fileType);
            broadcast.put("fileName", fileName);

            TextMessage outgoing = new TextMessage(broadcast.toString());
            Set<WebSocketSession> targets = roomSessions.get(roomName);
            if (targets != null) {
                for (WebSocketSession s : targets) {
                    if (s.isOpen()) {
                        try { s.sendMessage(outgoing); }
                        catch (Exception e) {
                            System.err.println("Send failed for session " + s.getId() + ": " + e.getMessage());
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("handleTextMessage error: " + e.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session,
                                      @Nullable CloseStatus status) {
        String username = (String) session.getAttributes().get("username");
        String roomName = (String) session.getAttributes().get("roomName");
        if (username == null || roomName == null) return;

        String key = sessionKey(username, roomName);
        // Only remove if this is still the registered session for that key
        userRoomSessions.remove(key, session);
        removeFromRoom(session, roomName);
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    /** Composite key: one slot per (user, room) pair */
    private static String sessionKey(String username, String roomName) {
        return username + "::" + roomName;
    }

    private void removeFromRoom(WebSocketSession session, String roomName) {
        Set<WebSocketSession> sessions = roomSessions.get(roomName);
        if (sessions != null) {
            sessions.remove(session);
            if (sessions.isEmpty()) roomSessions.remove(roomName);
        }
    }
}
