package com.example.websocket.service;

import com.example.websocket.model.*;
import com.example.websocket.model.User;
import com.example.websocket.repo.*;
import jakarta.transaction.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.Set;

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

    public Set<User> getUsersByRoomName(String roomName) {
        return chatRoomUserRepository.findUsersByRoomName(roomName);
    }

    public List<String> getRoomsByUserName(String username) {
        return chatRoomUserRepository.findRoomNamesByUsername(username);
    }


    public List<Message> getMessagesByRoomName(String roomName) {
        ChatRoom chatRoom = chatRoomRepository.findByRoomName(roomName)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Room not found"));
        return messageRepository.findMessagesByChatRoom(chatRoom);
    }

    @Transactional
    public ChatRoom createOrUpdateChatRoom(String roomName, Long userId) {
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