package com.example.websocket.controller;

import com.example.websocket.model.ChatRoom;
import com.example.websocket.model.Message;
import com.example.websocket.service.ChatRoomService;
import com.example.websocket.service.ChatServiceIml;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

@RestController
public class ChatController {

    private final ChatServiceIml chatService;
    private final ChatRoomService chatRoomService;

    public ChatController(ChatServiceIml chatService, ChatRoomService chatRoomService) {
        this.chatService = chatService;
        this.chatRoomService = chatRoomService;
    }

    @GetMapping("/api/chat/history/{roomName}")
    public List<Message> getRoomChatHistory(@PathVariable String roomName) {
        ChatRoom chatRoom = chatRoomService.createOrGetChatRoom(roomName, Set.of());
        return chatService.getMessagesByChatRoom(chatRoom);
    }

}
