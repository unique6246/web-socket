package com.example.websocket.service;

import com.example.websocket.model.*;
import com.example.websocket.model.User;
import com.example.websocket.repo.*;

import jakarta.transaction.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class ChatRoomService {

    @Autowired
    private ChatRoomUserRepository chatRoomUserRepository;

    @Autowired
    private ChatRoomRepository chatRoomRepository;

    @Autowired
    private UserRepository userRepository;

    public Set<User> getUsersByRoomName(String roomName) {
        return chatRoomUserRepository.findUsersByRoomName(roomName);
    }

    @Transactional
    public ChatRoom createOrGetChatRoom(String roomName, Set<Long> userIds) {
        Optional<ChatRoom> chatRoomOptional = chatRoomRepository.findByRoomName(roomName);
        ChatRoom existingRoom;

        if (chatRoomOptional.isPresent()) {
            existingRoom = chatRoomOptional.get();

            // Fetch IDs of users already in the room
            Set<Long> existingUserIds = existingRoom.getChatRoomUsers().stream()
                    .map(chatRoomUser -> chatRoomUser.getUser().getId())
                    .collect(Collectors.toSet());

            for (Long userId : userIds) {
                // Add only if user is not already associated
                if (!existingUserIds.contains(userId)) {
                    userRepository.findById(userId).ifPresent(user -> {
                        ChatRoomUser chatRoomUser = new ChatRoomUser();
                        chatRoomUser.setChatRoom(existingRoom);
                        chatRoomUser.setUser(user);
                        existingRoom.getChatRoomUsers().add(chatRoomUser);
                    });
                }
            }
        } else {
            // Create new room and link all users via ChatRoomUser
            existingRoom = new ChatRoom();
            existingRoom.setRoomName(roomName);

            Set<ChatRoomUser> chatRoomUsers = new HashSet<>();
            for (Long userId : userIds) {
                userRepository.findById(userId).ifPresent(user -> {
                    ChatRoomUser chatRoomUser = new ChatRoomUser();
                    chatRoomUser.setChatRoom(existingRoom);
                    chatRoomUser.setUser(user);
                    chatRoomUsers.add(chatRoomUser);
                });
            }
            existingRoom.setChatRoomUsers(chatRoomUsers);
        }

        // Persist the room along with its user associations only once
        return chatRoomRepository.save(existingRoom);
    }



}