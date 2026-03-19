package com.example.websocket.admin;

import com.example.websocket.model.ChatRoom;
import com.example.websocket.model.ChatRoomUser;
import com.example.websocket.model.Role;
import com.example.websocket.model.User;
import com.example.websocket.repo.ChatRoomRepository;
import com.example.websocket.repo.MessageRepository;
import com.example.websocket.repo.RoleRepository;
import com.example.websocket.repo.UserRepository;
import com.example.websocket.service.AuthService;
import com.example.websocket.service.ChatRoomService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

    private final UserRepository userRepository;
    private final ChatRoomRepository chatRoomRepository;
    private final MessageRepository messageRepository;
    private final RoleRepository roleRepository;
    private final AuthService authService;
    private final ChatRoomService chatRoomService;

    @Autowired
    public AdminController(UserRepository userRepository,
                           ChatRoomRepository chatRoomRepository,
                           MessageRepository messageRepository,
                           RoleRepository roleRepository,
                           AuthService authService,
                           ChatRoomService chatRoomService) {
        this.userRepository = userRepository;
        this.chatRoomRepository = chatRoomRepository;
        this.messageRepository = messageRepository;
        this.roleRepository = roleRepository;
        this.authService = authService;
        this.chatRoomService = chatRoomService;
    }

    // ── User Management ──────────────────────────────────

    @GetMapping("/users")
    public List<User> getAllUsers() {
        return userRepository.findAll();
    }

    @GetMapping("/users/{id}")
    public ResponseEntity<User> getUserById(@PathVariable Long id) {
        return userRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/users")
    public ResponseEntity<?> createUser(@RequestBody User user) {
        return authService.registerUser(user);
    }

    @DeleteMapping("/users/{id}")
    public ResponseEntity<Void> deleteUser(@PathVariable Long id) {
        if (!userRepository.existsById(id)) return ResponseEntity.notFound().build();
        userRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/users/{username}/roles/assign")
    public ResponseEntity<?> assignRole(@PathVariable String username, @RequestBody Map<String, String> body) {
        String roleName = body.get("role");
        return authService.assignRole(username, roleName);
    }

    @PutMapping("/users/{username}/roles/remove")
    public ResponseEntity<?> removeRole(@PathVariable String username, @RequestBody Map<String, String> body) {
        String roleName = body.get("role");
        return authService.removeRole(username, roleName);
    }

    // ── Chat Room Management ──────────────────────────────

    @GetMapping("/chatrooms")
    public List<ChatRoom> getAllChatRooms() {
        return chatRoomRepository.findAll();
    }

    @GetMapping("/chatrooms/{id}")
    public ResponseEntity<ChatRoom> getChatRoomById(@PathVariable Long id) {
        return chatRoomRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/chatrooms")
    public ResponseEntity<?> createChatRoom(@RequestBody Map<String, String> body,
                                             @AuthenticationPrincipal UserDetails userDetails) {
        String roomName = body.get("roomName");
        if (roomName == null || roomName.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Room name is required."));
        }
        String adminUsername = userDetails.getUsername();
        // createGroupRoom adds the admin as the first member with groupAdmin = true
        ChatRoom room = chatRoomService.createGroupRoom(
                roomName,
                java.util.List.of(adminUsername),
                adminUsername
        );
        return ResponseEntity.ok(room);
    }

    @DeleteMapping("/chatrooms/{id}")
    public ResponseEntity<Void> deleteChatRoom(@PathVariable Long id) {
        chatRoomService.deleteRoom(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/chatrooms/{id}/users")
    public ResponseEntity<Set<User>> getUsersInChatRoom(@PathVariable Long id) {
        return chatRoomRepository.findById(id).map(room -> {
            Set<User> users = room.getChatRoomUsers().stream()
                    .map(ChatRoomUser::getUser)
                    .collect(Collectors.toSet());
            return ResponseEntity.ok(users);
        }).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/users/{id}/chatrooms")
    public ResponseEntity<Set<ChatRoom>> getChatRoomsForUser(@PathVariable Long id) {
        return userRepository.findById(id).map(u -> {
            Set<ChatRoom> chatRooms = u.getChatRoomUsers().stream()
                    .map(ChatRoomUser::getChatRoom)
                    .collect(Collectors.toSet());
            return ResponseEntity.ok(chatRooms);
        }).orElse(ResponseEntity.notFound().build());
    }

    // ── Roles ────────────────────────────────────────────

    @GetMapping("/roles")
    public List<Role> getAllRoles() {
        return roleRepository.findAll();
    }

    // ── Statistics ────────────────────────────────────────

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Long>> getStats() {
        Map<String, Long> stats = new LinkedHashMap<>();
        stats.put("totalUsers", userRepository.count());
        stats.put("totalChatRooms", chatRoomRepository.count());
        stats.put("totalMessages", messageRepository.count());
        return ResponseEntity.ok(stats);
    }

    @GetMapping("/chatrooms/count")
    public ResponseEntity<Long> getChatRoomCount() {
        return ResponseEntity.ok(chatRoomRepository.count());
    }

    @GetMapping("/users/{id}/chatrooms/count")
    public ResponseEntity<Long> getChatRoomCountForUser(@PathVariable Long id) {
        return userRepository.findById(id).map(u -> {
            long count = u.getChatRoomUsers().size();
            return ResponseEntity.ok(count);
        }).orElse(ResponseEntity.notFound().build());
    }
}
