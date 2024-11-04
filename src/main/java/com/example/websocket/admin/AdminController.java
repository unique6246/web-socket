package com.example.websocket.admin;

import com.example.websocket.model.ChatRoom;
import com.example.websocket.model.ChatRoomUser;
import com.example.websocket.model.User;
import com.example.websocket.repo.ChatRoomRepository;
import com.example.websocket.repo.ChatRoomUserRepository;
import com.example.websocket.repo.UserRepository;
import com.example.websocket.service.AuthService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/admin")
public class AdminController {

    private final UserRepository userRepository;
    private final ChatRoomRepository chatRoomRepository;
    private final AuthService authService;

    @Autowired
    public AdminController(UserRepository userRepository,
                           ChatRoomRepository chatRoomRepository,
                           AuthService authService) {
        this.userRepository = userRepository;
        this.chatRoomRepository = chatRoomRepository;
        this.authService=authService;
    }

    // User Management Endpoints
    // -------------------------

    // Get all users
    @GetMapping("/users")
    public List<User> getAllUsers() {
        return userRepository.findAll();
    }

    // Get user by ID
    @GetMapping("/users/{id}")
    public ResponseEntity<User> getUserById(@PathVariable Long id) {
        Optional<User> user = userRepository.findById(id);
        return user.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    // Create new user
    @PostMapping("/users")
    public ResponseEntity<?> createUser(@RequestBody User user) {
        return authService.registerUser(user);
    }

    // Delete a user by ID
    @DeleteMapping("/users/{id}")
    public ResponseEntity<Void> deleteUser(@PathVariable Long id) {
        userRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    // Chat Room Management Endpoints
    // ------------------------------

    // Get all chat rooms
    @GetMapping("/chatrooms")
    public List<ChatRoom> getAllChatRooms() {
        return chatRoomRepository.findAll();
    }

    // Get chat room by ID
    @GetMapping("/chatrooms/{id}")
    public ResponseEntity<ChatRoom> getChatRoomById(@PathVariable Long id) {
        Optional<ChatRoom> chatRoom = chatRoomRepository.findById(id);
        return chatRoom.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    // Create a new chat room
    @PostMapping("/chatrooms")
    public ResponseEntity<ChatRoom> createChatRoom(@RequestBody ChatRoom chatRoom) {
        return ResponseEntity.ok(chatRoomRepository.save(chatRoom));
    }

    // Delete a chat room by ID
    @DeleteMapping("/chatrooms/{id}")
    public ResponseEntity<Void> deleteChatRoom(@PathVariable Long id) {
        chatRoomRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    // Get all users in a specific chat room
    @GetMapping("/chatrooms/{id}/users")
    public ResponseEntity<Set<User>> getUsersInChatRoom(@PathVariable Long id) {
        Optional<ChatRoom> chatRoom = chatRoomRepository.findById(id);
        return chatRoom.map(room -> {
            Set<User> users = room.getChatRoomUsers()
                    .stream()
                    .map(ChatRoomUser::getUser)
                    .collect(Collectors.toSet());
            return ResponseEntity.ok(users);
        }).orElseGet(() -> ResponseEntity.notFound().build());
    }

    // Get all chat rooms for a specific user
    @GetMapping("/users/{id}/chatrooms")
    public ResponseEntity<Set<ChatRoom>> getChatRoomsForUser(@PathVariable Long id) {
        Optional<User> user = userRepository.findById(id);
        return user.map(u -> {
            Set<ChatRoom> chatRooms = u.getChatRoomUsers()
                    .stream()
                    .map(ChatRoomUser::getChatRoom)
                    .collect(Collectors.toSet());
            return ResponseEntity.ok(chatRooms);
        }).orElseGet(() -> ResponseEntity.notFound().build());
    }

    // Statistics Endpoints
    // --------------------

    // Get the total number of chat rooms
    @GetMapping("/chatrooms/count")
    public ResponseEntity<Long> getChatRoomCount() {
        long count = chatRoomRepository.count();
        return ResponseEntity.ok(count);
    }

    // Get the number of chat rooms a specific user is a member of
    @GetMapping("/users/{id}/chatrooms/count")
    public ResponseEntity<Long> getChatRoomCountForUser(@PathVariable Long id) {
        Optional<User> user = userRepository.findById(id);
        return user.map(u -> {
            long count = u.getChatRoomUsers().size();
            return ResponseEntity.ok(count);
        }).orElseGet(() -> ResponseEntity.notFound().build());
    }
}
