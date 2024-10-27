package com.example.websocket.config;

import com.example.websocket.model.*;
import com.example.websocket.repo.*;
import com.example.websocket.service.ChatRoomService;
import org.json.JSONObject;
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
    // Map to store sessions per user (username)
    private static final Map<String, WebSocketSession> userSessions = new ConcurrentHashMap<>();

    // Map to store sessions per room
    private static final Map<String, Set<WebSocketSession>> roomSessions = new ConcurrentHashMap<>();

    private final ChatRoomService chatRoomService;
    private final MessageRepository chatMessageRepository;
    private final UserRepository userRepository;

    public SocketConnectionHandler(ChatRoomService chatRoomService, MessageRepository chatMessageRepository, UserRepository userRepository) {
        this.chatRoomService = chatRoomService;
        this.chatMessageRepository = chatMessageRepository;
        this.userRepository = userRepository;
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

            System.out.println("User " + username + " connected to room " + roomName);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        try {
            JSONObject jsonMessage = new JSONObject(message.getPayload());

            String sender = jsonMessage.optString("sender", null);
            String roomName = jsonMessage.optString("room", null);
            String msgContent = jsonMessage.optString("message", null);

            if (sender == null || roomName == null || msgContent == null) {
                System.err.println("Invalid message format from session: " + session.getId());
                return;
            }

            ChatRoom chatRoom = chatRoomService.createOrUpdateChatRoom(roomName, userRepository.findByUsername(sender).getId());

            Message msg = new Message();
            msg.setContent(msgContent);
            msg.setSender(sender);
            msg.setChatRoom(chatRoom);
            msg.setTimestamp(LocalDateTime.now());
            chatMessageRepository.save(msg);

            JSONObject sendMessage = new JSONObject();
            sendMessage.put("sender", sender);
            sendMessage.put("message", msgContent);

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
            System.out.println("User " + username + " disconnected");
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
                System.out.println("Disconnected user " + username + " from previous room " + previousRoomName);
            } catch (Exception e) {
                System.err.println("Failed to close session for user " + username + ": " + e.getMessage());
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
