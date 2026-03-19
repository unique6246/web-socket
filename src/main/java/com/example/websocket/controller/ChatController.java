package com.example.websocket.controller;

import com.example.websocket.JWT.JwtService;
import com.example.websocket.model.*;
import com.example.websocket.repo.UserRepository;
import com.example.websocket.service.*;
import com.example.websocket.JWT.JwtUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
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

    /** Create or join a plain named room (legacy) */
    @PostMapping("/rooms/create/{roomName}")
    public ChatRoom createRoom(@PathVariable String roomName, HttpServletRequest request) {
        String token = jwtService.extractToken(request);
        String username = jwtUtil.extractUsername(token);
        return chatRoomService.createOrUpdateChatRoom(roomName, username);
    }

    /** Start or retrieve a 1-on-1 DM with another user */
    @PostMapping("/rooms/dm/{otherUsername}")
    public ResponseEntity<?> startDm(@PathVariable String otherUsername, HttpServletRequest request) {
        String token = jwtService.extractToken(request);
        String currentUser = jwtUtil.extractUsername(token);
        if (currentUser.equals(otherUsername)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Cannot DM yourself"));
        }
        ChatRoom room = chatRoomService.createOrGetDmRoom(currentUser, otherUsername);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("roomName", room.getRoomName());
        resp.put("type", room.getType());
        resp.put("displayName", otherUsername); // the name to show in sidebar
        return ResponseEntity.ok(resp);
    }

    /** Create a named group room with selected members */
    @PostMapping("/rooms/group")
    public ResponseEntity<?> createGroup(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        String token = jwtService.extractToken(request);
        String currentUser = jwtUtil.extractUsername(token);

        String groupName = (String) body.get("groupName");
        if (groupName == null || groupName.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Group name is required"));
        }

        @SuppressWarnings("unchecked")
        List<String> members = (List<String>) body.get("members");
        if (members == null) members = new ArrayList<>();

        // Always include the creator first (so they get ADMIN role)
        Set<String> memberSet = new LinkedHashSet<>();
        memberSet.add(currentUser);
        memberSet.addAll(members);

        ChatRoom room = chatRoomService.createGroupRoom(groupName, new ArrayList<>(memberSet), currentUser);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("roomName", room.getRoomName());
        resp.put("type", room.getType());
        resp.put("groupRole", "ADMIN"); // caller is always admin
        return ResponseEntity.ok(resp);
    }

    /** Returns the caller's role in the room: { groupRole: "ADMIN" | "MEMBER" } */
    @GetMapping("/rooms/{roomName}/my-role")
    public ResponseEntity<?> getMyRole(@PathVariable String roomName, HttpServletRequest request) {
        String token = jwtService.extractToken(request);
        String username = jwtUtil.extractUsername(token);
        String role = chatRoomService.getMyRoleInRoom(roomName, username);
        return ResponseEntity.ok(Map.of("groupRole", role));
    }

    /** Remove a member from a group — only group admin can call this */
    @DeleteMapping("/rooms/{roomName}/remove-member/{username}")
    public ResponseEntity<?> removeMember(@PathVariable String roomName,
                                          @PathVariable String username,
                                          HttpServletRequest request) {
        String token = jwtService.extractToken(request);
        String caller = jwtUtil.extractUsername(token);
        chatRoomService.removeMemberByAdmin(roomName, caller, username);
        return ResponseEntity.ok(Map.of("removed", username));
    }

    /** List all users except the caller – for the People tab */
    @GetMapping("/users")
    public ResponseEntity<List<Map<String, String>>> listUsers(HttpServletRequest request) {
        String token = jwtService.extractToken(request);
        String currentUser = jwtUtil.extractUsername(token);

        List<Map<String, String>> users = userRepository.findAll().stream()
                .filter(u -> !u.getUsername().equals(currentUser))
                .map(u -> {
                    Map<String, String> m = new LinkedHashMap<>();
                    m.put("username", u.getUsername());
                    return m;
                })
                .collect(Collectors.toList());
        return ResponseEntity.ok(users);
    }

    /** Rooms the current user belongs to, including type field */
    @GetMapping("/my-rooms")
    public ResponseEntity<List<Map<String, String>>> myRooms(HttpServletRequest request) {
        String token = jwtService.extractToken(request);
        String username = jwtUtil.extractUsername(token);
        return ResponseEntity.ok(chatRoomService.getRoomsWithTypeByUserName(username));
    }

    @GetMapping("/history/{roomName}")
    public List<Message> getRoomChatHistory(@PathVariable String roomName) {
        return chatRoomService.getMessagesByRoomName(roomName);
    }

    @DeleteMapping("/rooms/{roomName}/leave")
    public ResponseEntity<?> leaveRoom(@PathVariable String roomName, HttpServletRequest request) {
        String token = jwtService.extractToken(request);
        String username = jwtUtil.extractUsername(token);
        chatRoomService.removeUserFromRoom(username, roomName);
        return ResponseEntity.ok(Map.of("message", "Left room successfully"));
    }

    /** Add an existing user to an existing group room */
    @PostMapping("/rooms/{roomName}/add-member/{username}")
    public ResponseEntity<?> addMemberToGroup(@PathVariable String roomName,
                                              @PathVariable String username,
                                              HttpServletRequest request) {
        String token = jwtService.extractToken(request);
        String caller = jwtUtil.extractUsername(token);
        ChatRoom room = chatRoomService.getRoomDetails(roomName);
        if (!"GROUP".equals(room.getType())) {
            return ResponseEntity.badRequest().body(Map.of("error", "Cannot add members to a DM room"));
        }
        chatRoomService.createOrUpdateChatRoom(roomName, username);
        Map<String, String> resp = new LinkedHashMap<>();
        resp.put("roomName", roomName);
        resp.put("addedUser", username);
        return ResponseEntity.ok(resp);
    }

    /** Returns members with their groupAdmin flag — used by the members panel */
    @GetMapping("/rooms/{roomName}/members")
    public ResponseEntity<List<Map<String, Object>>> getRoomMembers(@PathVariable String roomName) {
        ChatRoom room = chatRoomService.getRoomDetails(roomName);
        List<Map<String, Object>> members = room.getChatRoomUsers().stream()
                .map(cu -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("username", cu.getUser().getUsername());
                    m.put("groupAdmin", cu.isGroupAdmin());
                    return m;
                })
                .collect(Collectors.toList());
        return ResponseEntity.ok(members);
    }

    @GetMapping("/rooms/{roomName}")
    public ResponseEntity<ChatRoom> getRoomDetails(@PathVariable String roomName) {
        return ResponseEntity.ok(chatRoomService.getRoomDetails(roomName));
    }
}
