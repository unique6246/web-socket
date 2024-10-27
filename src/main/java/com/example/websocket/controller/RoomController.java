package com.example.websocket.controller;

import com.example.websocket.service.ChatRoomService;
import com.example.websocket.service.JwtService;
import com.example.websocket.utility.JwtUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
public class RoomController {


    private final ChatRoomService chatRoomService;
    private final JwtService jwtService;
    private final JwtUtil jwtUtil;

    public RoomController(ChatRoomService chatRoomService, JwtService jwtService, JwtUtil jwtUtil) {
        this.chatRoomService = chatRoomService;
        this.jwtService = jwtService;
        this.jwtUtil = jwtUtil;
    }

    @GetMapping("/api/rooms")
    public ResponseEntity<List<Map<String, String>>> rooms(HttpServletRequest request) {
        String token = jwtService.extractToken(request);
        String username = jwtUtil.extractUsername(token);

        // Fetch the list of room names
        List<String> roomNames = chatRoomService.getRoomsByUserName(username);

        // Convert the list of room names into a list of maps
        List<Map<String, String>> roomObjects = roomNames.stream()
                .map(roomName -> {
                    Map<String, String> roomMap = new HashMap<>();
                    roomMap.put("roomName", roomName);
                    return roomMap;
                })
                .collect(Collectors.toList());

        return ResponseEntity.ok(roomObjects);
    }

}
