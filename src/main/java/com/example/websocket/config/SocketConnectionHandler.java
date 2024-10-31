package com.example.websocket.config;

import com.example.websocket.service.ChatRoomService;
import org.json.JSONObject;
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
    // Map to store sessions per user (username)
    private static final Map<String, WebSocketSession> userSessions = new ConcurrentHashMap<>();

    // Map to store sessions per room
    private static final Map<String, Set<WebSocketSession>> roomSessions = new ConcurrentHashMap<>();

    private final ChatRoomService chatRoomService;

    public SocketConnectionHandler(ChatRoomService chatRoomService) {
        this.chatRoomService = chatRoomService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String username = (String) session.getAttributes().get("username");
        String roomName = (String) session.getAttributes().get("roomName");

        if (username != null && roomName != null) {
            // Disconnect the user from any existing room and remove their session
            disconnectAndRemovePreviousSession(username);

            // Track user session in the new room
            userSessions.put(username, session);
            roomSessions.computeIfAbsent(roomName, k -> new CopyOnWriteArraySet<>()).add(session);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        try {
            JSONObject jsonMessage = new JSONObject(message.getPayload());

            String sender = jsonMessage.optString("sender", null);
            String roomName = jsonMessage.optString("room", null);
            String msgContent = jsonMessage.optString("message", null);
            String fileUrl = jsonMessage.optString("fileUrl", null);
            String fileType = jsonMessage.optString("fileType", null);
            String fileName = jsonMessage.optString("fileName", null);

            chatRoomService.saveMessage (roomName, sender, msgContent, fileUrl, fileType, fileName);



            JSONObject sendMessage = new JSONObject();
            sendMessage.put("sender", sender);
            sendMessage.put("message", msgContent);
            sendMessage.put("roomName", roomName);
            sendMessage.put("fileUrl", fileUrl);
            sendMessage.put("fileType", fileType);
            sendMessage.put("fileName", fileName);

            Set<WebSocketSession> roomUsers = roomSessions.get(roomName);
            if (roomUsers != null) {
                for (WebSocketSession userSession : roomUsers) {
                    if (userSession.isOpen()) {
                        try {
                            userSession.sendMessage(new TextMessage(sendMessage.toString()));
                        } catch (Exception e) {
                            System.err.println("Failed to send message to user " + userSession.getId() + ": " + e.getMessage());
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Error handling message: " + e.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String username = (String) session.getAttributes().get("username");
        String roomName = (String) session.getAttributes().get("roomName");

        if (username != null) {
            // Remove user session when they disconnect
            removeUserSession(username, roomName);
        }
    }

    // Helper method to disconnect and remove previous session for a user
    private void disconnectAndRemovePreviousSession(String username) {
        WebSocketSession existingSession = userSessions.get(username);
        if (existingSession != null) {
            String previousRoomName = (String) existingSession.getAttributes().get("roomName");
            removeUserSession(username, previousRoomName);

            // Close previous session
            try {
                existingSession.close();
            } catch (Exception ignored) {
            }
        }
    }

    // Helper method to remove a user's session from maps
    private void removeUserSession(String username, String roomName) {
        WebSocketSession session = userSessions.remove(username);
        if (session != null && roomName != null && roomSessions.containsKey(roomName)) {
            Set<WebSocketSession> sessions = roomSessions.get(roomName);
            sessions.remove(session);
            if (sessions.isEmpty()) {
                roomSessions.remove(roomName); // Cleanup room if empty
            }
        }
    }
}
