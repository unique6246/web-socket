package com.example.websocket.webconfig;

import com.example.websocket.model.ChatRoom;
import com.example.websocket.model.Message;
import com.example.websocket.repo.MessageRepository;
import com.example.websocket.repo.UserRepository;
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

@Component
public class SocketConnectionHandler extends TextWebSocketHandler {
    // Map to store sessions per user (username)
    private static final Map<String, WebSocketSession> userSessions = new ConcurrentHashMap<>();

    private final ChatRoomService chatRoomService;
    private final MessageRepository chatMessageRepository;
    private final UserRepository userRepository;

    public SocketConnectionHandler(ChatRoomService chatRoomService, MessageRepository chatMessageRepository, UserRepository userRepository) {
        this.chatRoomService = chatRoomService;
        this.chatMessageRepository=chatMessageRepository;
        this.userRepository = userRepository;
    }


    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        // Extract username from session attributes (set in the HandshakeInterceptor)
        String username = (String) session.getAttributes().get("username");
        if (username != null) {
            userSessions.put(username, session);
            System.out.println("User " + username + " connected");
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        JSONObject jsonMessage = new JSONObject(message.getPayload());
        String sender = jsonMessage.getString("sender");
        String roomName = jsonMessage.getString("room");
        String msgContent = jsonMessage.getString("message");

        // Create or retrieve the chat room
        ChatRoom chatRoom = chatRoomService.createOrGetChatRoom(roomName, Set.of(userRepository.findByUsername(sender).getId()));

        // Save the message
        Message msg = new Message();
        msg.setContent(msgContent);
        msg.setSender(sender);
        msg.setChatRoom(chatRoom);
        msg.setTimestamp(LocalDateTime.now());
        chatMessageRepository.save(msg);

        // Broadcast message to all users in the room
        for (WebSocketSession userSession : userSessions.values()) {
            if (userSession.isOpen()) {
                JSONObject sendMessage = new JSONObject();
                sendMessage.put("sender", sender);
                sendMessage.put("message", msgContent);
                userSession.sendMessage(new TextMessage(sendMessage.toString()));
            }
        }
    }


    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        // Remove user from the session map on disconnect
        String username = (String) session.getAttributes().get("username");
        if (username != null) {
            userSessions.remove(username);
            System.out.println("User " + username + " disconnected");
        }
    }
}