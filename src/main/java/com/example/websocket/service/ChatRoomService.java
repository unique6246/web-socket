package com.example.websocket.service;

import com.example.websocket.model.*;
import com.example.websocket.repo.*;
import jakarta.transaction.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class ChatRoomService {

    @Autowired
    private ChatRoomUserRepository chatRoomUserRepository;

    @Autowired
    private ChatRoomRepository chatRoomRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private MessageRepository messageRepository;

    @Cacheable(value = "chatRoomsByName", key = "#username")
    public List<String> getRoomsByUserName(String username) {
        return chatRoomUserRepository.findRoomNamesByUsername(username);
    }

    @Cacheable(value = "chatMessagesByRoom", key = "#roomName")
    public List<Message> getMessagesByRoomName(String roomName) {
        ChatRoom chatRoom = chatRoomRepository.findByRoomName(roomName)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Room not found"));
        return messageRepository.findMessagesByChatRoom(chatRoom);
    }

    @Transactional
    @CacheEvict(value = "chatMessagesByRoom", key = "#roomName")
    public void saveMessage(String roomName, String sender, String content) {
        ChatRoom chatRoom = chatRoomRepository.findByRoomName(roomName)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Room not found"));

        Message msg = new Message();
        msg.setContent(content);
        msg.setSender(sender);
        msg.setChatRoom(chatRoom);
        msg.setTimestamp(LocalDateTime.now());

        // Save the message to the repository
        messageRepository.save(msg);
    }

    @Transactional
    @CacheEvict(value = "chatRoomsByName", key = "#username")
    public ChatRoom createOrUpdateChatRoom(String roomName, String username) {
        Long userId = userRepository.findByUsername(username).getId();
        Optional<ChatRoom> chatRoomOptional = chatRoomRepository.findByRoomName(roomName);
        ChatRoom room;

        if (chatRoomOptional.isPresent()) {
            room = chatRoomOptional.get();

            // Check if the user is already in the room
            boolean userAlreadyInRoom = room.getChatRoomUsers().stream()
                    .anyMatch(chatRoomUser -> chatRoomUser.getUser().getId().equals(userId));

            // If the user is not already in the room, add them
            if (!userAlreadyInRoom) {
                userRepository.findById(userId).ifPresent(user -> {
                    ChatRoomUser chatRoomUser = new ChatRoomUser();
                    chatRoomUser.setChatRoom(room);
                    chatRoomUser.setUser(user);
                    room.getChatRoomUsers().add(chatRoomUser);
                });
            }
        } else {
            // Create a new room with the current user as the first participant
            room = new ChatRoom();
            room.setRoomName(roomName);

            userRepository.findById(userId).ifPresent(user -> {
                ChatRoomUser chatRoomUser = new ChatRoomUser();
                chatRoomUser.setChatRoom(room);
                chatRoomUser.setUser(user);
                room.getChatRoomUsers().add(chatRoomUser);
            });

            chatRoomRepository.save(room); // Persist the new chat room
        }
        return room;
    }
}
