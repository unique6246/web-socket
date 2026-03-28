package com.example.websocket.controller;

import com.example.websocket.JWT.JwtService;
import com.example.websocket.JWT.JwtUtil;
import com.example.websocket.model.*;
import com.example.websocket.repo.UserRepository;
import com.example.websocket.service.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatRoomService chatRoomService;
    private final JwtService jwtService;
    private final JwtUtil jwtUtil;
    private final UserRepository userRepository;

    public ChatController(ChatRoomService chatRoomService, JwtService jwtService,
                          JwtUtil jwtUtil, UserRepository userRepository) {
        this.chatRoomService = chatRoomService;
        this.jwtService = jwtService;
        this.jwtUtil = jwtUtil;
        this.userRepository = userRepository;
    }

    /** Create or join a plain named room (legacy) — returns safe DTO */
    @PostMapping("/rooms/create/{roomName}")
    @Transactional
    public Map<String, Object> createRoom(@PathVariable String roomName, HttpServletRequest request) {
        String username = extractUsername(request);
        ChatRoom room = chatRoomService.createOrUpdateChatRoom(roomName, username);
        return toRoomDto(room);
    }

    /** Start or retrieve a 1-on-1 DM */
    @PostMapping("/rooms/dm/{otherUsername}")
    public ResponseEntity<?> startDm(@PathVariable String otherUsername, HttpServletRequest request) {
        String currentUser = extractUsername(request);
        if (currentUser.equals(otherUsername))
            return ResponseEntity.badRequest().body(Map.of("error", "Cannot DM yourself"));
        ChatRoom room = chatRoomService.createOrGetDmRoom(currentUser, otherUsername);
        return ResponseEntity.ok(Map.of(
            "roomName", room.getRoomName(),
            "type",     room.getType(),
            "displayName", otherUsername
        ));
    }

    /** Create a named group room */
    @PostMapping("/rooms/group")
    public ResponseEntity<?> createGroup(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        String currentUser = extractUsername(request);
        String groupName = (String) body.get("groupName");
        if (groupName == null || groupName.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "Group name is required"));

        @SuppressWarnings("unchecked")
        List<String> members = body.get("members") instanceof List ? (List<String>) body.get("members") : new ArrayList<>();

        Set<String> memberSet = new LinkedHashSet<>();
        memberSet.add(currentUser);
        memberSet.addAll(members);

        ChatRoom room = chatRoomService.createGroupRoom(groupName, new ArrayList<>(memberSet), currentUser);
        return ResponseEntity.ok(Map.of(
            "roomName",  room.getRoomName(),
            "type",      room.getType(),
            "groupRole", "ADMIN"
        ));
    }

    /** Caller's role in the room */
    @GetMapping("/rooms/{roomName}/my-role")
    public ResponseEntity<?> getMyRole(@PathVariable String roomName, HttpServletRequest request) {
        String username = extractUsername(request);
        return ResponseEntity.ok(Map.of("groupRole", chatRoomService.getMyRoleInRoom(roomName, username)));
    }

    /** Remove a member from a group (admin only) */
    @DeleteMapping("/rooms/{roomName}/remove-member/{username}")
    public ResponseEntity<?> removeMember(@PathVariable String roomName,
                                          @PathVariable String username,
                                          HttpServletRequest request) {
        String caller = extractUsername(request);
        chatRoomService.removeMemberByAdmin(roomName, caller, username);
        return ResponseEntity.ok(Map.of("removed", username));
    }

    /** All users except caller — for the People tab. Includes profile fields. */
    @GetMapping("/users")
    public ResponseEntity<List<Map<String, Object>>> listUsers(HttpServletRequest request) {
        String currentUser = extractUsername(request);
        List<Map<String, Object>> users = userRepository.findAll().stream()
                .filter(u -> !u.getUsername().equals(currentUser))
                .map(u -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("username",    u.getUsername());
                    m.put("displayName", u.getDisplayName() != null ? u.getDisplayName() : u.getUsername());
                    m.put("avatarUrl",   u.getAvatarUrl()   != null ? u.getAvatarUrl()   : "");
                    m.put("status",      u.getStatus()      != null ? u.getStatus().name() : "OFFLINE");
                    return m;
                })
                .collect(Collectors.toList());
        return ResponseEntity.ok(users);
    }

    /** Rooms the caller belongs to */
    @GetMapping("/my-rooms")
    public ResponseEntity<List<Map<String, String>>> myRooms(HttpServletRequest request) {
        String username = extractUsername(request);
        return ResponseEntity.ok(chatRoomService.getRoomsWithTypeByUserName(username));
    }

    /** Chat history (legacy — prefer /api/messages/room/{name}) */
    @GetMapping("/history/{roomName}")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getRoomChatHistory(@PathVariable String roomName) {
        return chatRoomService.getMessagesByRoomName(roomName).stream()
                .map(m -> {
                    Map<String, Object> dto = new LinkedHashMap<>();
                    dto.put("id",          m.getId());
                    dto.put("sender",      m.getSender());
                    dto.put("content",     m.isDeleted() ? null : m.getContent());
                    dto.put("fileUrl",     m.getFileUrl());
                    dto.put("fileName",    m.getFileName());
                    dto.put("fileType",    m.getFileType());
                    dto.put("messageType", m.getMessageType() != null ? m.getMessageType().name() : "TEXT");
                    dto.put("timestamp",   m.getTimestamp() != null ? m.getTimestamp().toString() : null);
                    dto.put("isEdited",    m.isEdited());
                    dto.put("isDeleted",   m.isDeleted());
                    dto.put("isPinned",    m.isPinned());
                    return dto;
                })
                .collect(Collectors.toList());
    }

    /** Leave a group room */
    @DeleteMapping("/rooms/{roomName}/leave")
    public ResponseEntity<?> leaveRoom(@PathVariable String roomName, HttpServletRequest request) {
        String username = extractUsername(request);
        chatRoomService.removeUserFromRoom(username, roomName);
        return ResponseEntity.ok(Map.of("message", "Left room successfully"));
    }

    /** Add a user to a group room */
    @PostMapping("/rooms/{roomName}/add-member/{username}")
    @Transactional(readOnly = true)
    public ResponseEntity<?> addMemberToGroup(@PathVariable String roomName,
                                              @PathVariable String username) {
        ChatRoom room = chatRoomService.getRoomDetails(roomName);
        if (!"GROUP".equals(room.getType()))
            return ResponseEntity.badRequest().body(Map.of("error", "Cannot add members to a DM room"));
        chatRoomService.createOrUpdateChatRoom(roomName, username);
        return ResponseEntity.ok(Map.of("roomName", roomName, "addedUser", username));
    }

    /** Room members list — used by the members panel */
    @GetMapping("/rooms/{roomName}/members")
    @Transactional(readOnly = true)
    public ResponseEntity<List<Map<String, Object>>> getRoomMembers(@PathVariable String roomName) {
        ChatRoom room = chatRoomService.getRoomDetails(roomName);
        List<Map<String, Object>> members = room.getChatRoomUsers().stream()
                .map(cu -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("username",    cu.getUser().getUsername());
                    m.put("displayName", cu.getUser().getDisplayName() != null
                                         ? cu.getUser().getDisplayName() : cu.getUser().getUsername());
                    m.put("avatarUrl",   cu.getUser().getAvatarUrl() != null
                                         ? cu.getUser().getAvatarUrl() : "");
                    m.put("groupAdmin",  cu.isGroupAdmin());
                    return m;
                })
                .collect(Collectors.toList());
        return ResponseEntity.ok(members);
    }

    /** Room details as a safe DTO (no lazy collections) */
    @GetMapping("/rooms/{roomName}")
    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, Object>> getRoomDetails(@PathVariable String roomName) {
        return ResponseEntity.ok(toRoomDto(chatRoomService.getRoomDetails(roomName)));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private String extractUsername(HttpServletRequest request) {
        return jwtUtil.extractUsername(jwtService.extractToken(request));
    }

    private Map<String, Object> toRoomDto(ChatRoom r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",          r.getId());
        m.put("roomName",    r.getRoomName());
        m.put("type",        r.getType()        != null ? r.getType()        : "GROUP");
        m.put("description", r.getDescription() != null ? r.getDescription() : "");
        m.put("avatarUrl",   r.getAvatarUrl()   != null ? r.getAvatarUrl()   : "");
        m.put("createdBy",   r.getCreatedBy()   != null ? r.getCreatedBy()   : "");
        m.put("createdAt",   r.getCreatedAt()   != null ? r.getCreatedAt().toString() : "");
        m.put("memberCount", r.getChatRoomUsers() != null ? r.getChatRoomUsers().size() : 0);
        return m;
    }
}
