package com.example.websocket.service;

import com.example.websocket.model.*;
import com.example.websocket.repo.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ChatServiceIml {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private MessageRepository messageRepository;
    @Autowired
    private ChatRoomRepository c;

    public List<Message> getMessagesByChatRoom(ChatRoom chatRoom) {
        return messageRepository.findMessagesByChatRoom(chatRoom);
    }
}
