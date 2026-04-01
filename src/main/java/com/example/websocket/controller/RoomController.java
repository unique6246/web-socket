package com.example.websocket.controller;

import com.example.websocket.service.ChatRoomService;
import com.example.websocket.JWT.JwtService;
import com.example.websocket.JWT.JwtUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;
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

    @GetMapping("/api/user/rooms")
    public ResponseEntity<List<Map<String, String>>> rooms(HttpServletRequest request) {
        String username = jwtUtil.extractUsername(jwtService.extractToken(request));
        return ResponseEntity.ok(
            chatRoomService.getRoomsByUserName(username).stream()
                .map(name -> Map.of("roomName", name))
                .collect(Collectors.toList())
        );
    }

    /** Search rooms — returns safe DTOs, no lazy collections */
    @GetMapping("/api/rooms/search")
    @Transactional(readOnly = true)
    public ResponseEntity<List<Map<String, Object>>> searchRooms(@RequestParam String query) {
        List<Map<String, Object>> result = chatRoomService.searchRoomsByName(query).stream()
                .map(r -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id",          r.getId());
                    m.put("roomName",    r.getRoomName());
                    m.put("type",        r.getType() != null ? r.getType() : "GROUP");
                    m.put("description", r.getDescription() != null ? r.getDescription() : "");
                    m.put("memberCount", r.getChatRoomUsers() != null ? r.getChatRoomUsers().size() : 0);
                    return m;
                })
                .collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }


}
