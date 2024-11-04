package com.example.websocket.controller;

import com.example.websocket.JWT.JwtService;
import com.example.websocket.model.*;
import com.example.websocket.service.*;
import com.example.websocket.JWT.JwtUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatRoomService chatRoomService;
    private final JwtService jwtService;
    private final JwtUtil jwtUtil;

    public ChatController(ChatRoomService chatRoomService, JwtService jwtService, JwtUtil jwtUtil) {
        this.chatRoomService = chatRoomService;
        this.jwtService = jwtService;
        this.jwtUtil = jwtUtil;
    }

    @PostMapping("/rooms/create/{roomName}")
    public ChatRoom createRoom(@PathVariable String roomName, HttpServletRequest request) {
        String token = jwtService.extractToken(request);
        String username=jwtUtil.extractUsername(token);

        return chatRoomService.createOrUpdateChatRoom(roomName, username );
    }

    @GetMapping("/history/{roomName}")
    public List<Message> getRoomChatHistory(@PathVariable String roomName) {
        return chatRoomService.getMessagesByRoomName(roomName);
    }
}
