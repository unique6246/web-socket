package com.example.websocket.admin;

import com.example.websocket.model.*;
import com.example.websocket.repo.*;
import com.example.websocket.service.AuthService;
import com.example.websocket.service.ChatRoomService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.transaction.annotation.Transactional;
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

    // ─────────────────────────────────────────────────────────────────────────
    //  USER MANAGEMENT
    // ─────────────────────────────────────────────────────────────────────────

    @GetMapping("/users")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getAllUsers() {
        return userRepository.findAll().stream()
                .map(this::toUserDto)
                .collect(Collectors.toList());
    }

    @GetMapping("/users/{id}")
    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, Object>> getUserById(@PathVariable Long id) {
        return userRepository.findById(id)
                .map(u -> ResponseEntity.ok(toUserDto(u)))
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
    public ResponseEntity<?> assignRole(@PathVariable String username,
                                         @RequestBody Map<String, String> body) {
        return authService.assignRole(username, body.get("role"));
    }

    @PutMapping("/users/{username}/roles/remove")
    public ResponseEntity<?> removeRole(@PathVariable String username,
                                         @RequestBody Map<String, String> body) {
        return authService.removeRole(username, body.get("role"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  CHAT ROOM MANAGEMENT
    // ─────────────────────────────────────────────────────────────────────────

    @GetMapping("/chatrooms")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getAllChatRooms() {
        return chatRoomRepository.findAll().stream()
                .map(this::toRoomDto)
                .collect(Collectors.toList());
    }

    @GetMapping("/chatrooms/{id}")
    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, Object>> getChatRoomById(@PathVariable Long id) {
        return chatRoomRepository.findById(id)
                .map(r -> ResponseEntity.ok(toRoomDto(r)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/chatrooms")
    @Transactional
    public ResponseEntity<?> createChatRoom(@RequestBody Map<String, String> body,
                                             @AuthenticationPrincipal UserDetails userDetails) {
        String roomName = body.get("roomName");
        if (roomName == null || roomName.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "Room name is required."));
        ChatRoom room = chatRoomService.createGroupRoom(
                roomName, List.of(userDetails.getUsername()), userDetails.getUsername());
        return ResponseEntity.ok(toRoomDto(room));
    }

    @DeleteMapping("/chatrooms/{id}")
    public ResponseEntity<Void> deleteChatRoom(@PathVariable Long id) {
        chatRoomService.deleteRoom(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/chatrooms/{id}/users")
    @Transactional(readOnly = true)
    public ResponseEntity<List<Map<String, Object>>> getUsersInRoom(@PathVariable Long id) {
        return chatRoomRepository.findById(id).map(room ->
            ResponseEntity.ok(
                room.getChatRoomUsers().stream()
                    .map(cru -> toUserDto(cru.getUser()))
                    .collect(Collectors.toList())
            )
        ).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/users/{id}/chatrooms")
    @Transactional(readOnly = true)
    public ResponseEntity<List<Map<String, Object>>> getRoomsForUser(@PathVariable Long id) {
        return userRepository.findById(id).map(u ->
            ResponseEntity.ok(
                u.getChatRoomUsers().stream()
                    .map(cru -> toRoomDto(cru.getChatRoom()))
                    .collect(Collectors.toList())
            )
        ).orElse(ResponseEntity.notFound().build());
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  ROLES
    // ─────────────────────────────────────────────────────────────────────────

    @GetMapping("/roles")
    public List<Map<String, Object>> getAllRoles() {
        return roleRepository.findAll().stream()
                .map(r -> Map.<String, Object>of("id", r.getId(), "name", r.getName()))
                .collect(Collectors.toList());
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  STATISTICS
    // ─────────────────────────────────────────────────────────────────────────

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Long>> getStats() {
        Map<String, Long> stats = new LinkedHashMap<>();
        stats.put("totalUsers",     userRepository.count());
        stats.put("totalChatRooms", chatRoomRepository.count());
        stats.put("totalMessages",  messageRepository.count());
        return ResponseEntity.ok(stats);
    }

    @GetMapping("/chatrooms/count")
    public ResponseEntity<Long> getChatRoomCount() {
        return ResponseEntity.ok(chatRoomRepository.count());
    }

    @GetMapping("/users/{id}/chatrooms/count")
    @Transactional(readOnly = true)
    public ResponseEntity<Long> getChatRoomCountForUser(@PathVariable Long id) {
        return userRepository.findById(id)
                .map(u -> ResponseEntity.ok((long) u.getChatRoomUsers().size()))
                .orElse(ResponseEntity.notFound().build());
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  DTO helpers  (never expose raw JPA entities to Jackson)
    // ─────────────────────────────────────────────────────────────────────────

    /** Flat, safe representation of a User — no lazy collections. */
    private Map<String, Object> toUserDto(User u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",           u.getId());
        m.put("username",     u.getUsername());
        m.put("email",        u.getEmail()       != null ? u.getEmail()       : "");
        m.put("displayName",  u.getDisplayName() != null ? u.getDisplayName() : u.getUsername());
        m.put("phone",        u.getPhone()       != null ? u.getPhone()       : "");
        m.put("avatarUrl",    u.getAvatarUrl()   != null ? u.getAvatarUrl()   : "");
        m.put("bio",          u.getBio()         != null ? u.getBio()         : "");
        m.put("status",       u.getStatus()      != null ? u.getStatus().name(): "OFFLINE");
        m.put("emailVerified", u.isEmailVerified());
        m.put("createdAt",    u.getCreatedAt()   != null ? u.getCreatedAt().toString() : "");
        // Roles are EAGER so safe to access
        List<Map<String, Object>> roles = u.getRoles().stream()
                .map(r -> Map.<String, Object>of("id", r.getId(), "name", r.getName()))
                .collect(Collectors.toList());
        m.put("roles", roles);
        return m;
    }

    /** Flat, safe representation of a ChatRoom — uses member count, not the full collection. */
    private Map<String, Object> toRoomDto(ChatRoom r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",          r.getId());
        m.put("roomName",    r.getRoomName());
        m.put("type",        r.getType()        != null ? r.getType()        : "GROUP");
        m.put("description", r.getDescription() != null ? r.getDescription() : "");
        m.put("avatarUrl",   r.getAvatarUrl()   != null ? r.getAvatarUrl()   : "");
        m.put("isPrivate",   r.isPrivate());
        m.put("createdBy",   r.getCreatedBy()   != null ? r.getCreatedBy()   : "");
        m.put("createdAt",   r.getCreatedAt()   != null ? r.getCreatedAt().toString() : "");
        // Safe: use size() while the collection is still in the open session (called from @Transactional method)
        m.put("memberCount", r.getChatRoomUsers() != null ? r.getChatRoomUsers().size() : 0);
        return m;
    }
}
