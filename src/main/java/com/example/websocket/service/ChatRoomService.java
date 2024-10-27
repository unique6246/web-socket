package com.example.websocket.service;

import com.example.websocket.model.*;
import com.example.websocket.model.User;
import com.example.websocket.repo.*;

import jakarta.transaction.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

@Service
public class ChatRoomService {

    @Autowired
    private ChatRoomRepository chatRoomRepository;

    @Autowired
    private UserRepository userRepository;

    @Transactional
    public ChatRoom createOrGetChatRoom(String roomName, Set<Long> userIds) {
        // Check if the chat room already exists
        Optional<ChatRoom> chatRoomOptional = chatRoomRepository.findByRoomName(roomName);

        // If it exists, return it
        if (chatRoomOptional.isPresent()) {
            return chatRoomOptional.get();
        } else {
            // Create new room
            ChatRoom newRoom = new ChatRoom();
            newRoom.setRoomName(roomName);

            // Add users to the room
            Set<User> users = new HashSet<>();
            for (Long userId : userIds) {
                userRepository.findById(userId).ifPresent(users::add);
            }
            newRoom.setUsers(users);
            return chatRoomRepository.save(newRoom);
        }
    }
}