package com.example.websocket.controller;

import com.example.websocket.JWT.JwtService;
import com.example.websocket.JWT.JwtUtil;
import com.example.websocket.fileStorage.FileStorageService;
import com.example.websocket.service.UserProfileService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/users")
@PreAuthorize("isAuthenticated()")
public class UserProfileController {

    private final UserProfileService userProfileService;
    private final JwtService jwtService;
    private final JwtUtil jwtUtil;
    private final FileStorageService fileStorageService;

    public UserProfileController(UserProfileService userProfileService,
                                  JwtService jwtService,
                                  JwtUtil jwtUtil,
                                  FileStorageService fileStorageService) {
        this.userProfileService = userProfileService;
        this.jwtService = jwtService;
        this.jwtUtil = jwtUtil;
        this.fileStorageService = fileStorageService;
    }

    /** Get any user's public profile */
    @GetMapping("/{username}/profile")
    public ResponseEntity<?> getProfile(@PathVariable String username) {
        return ResponseEntity.ok(userProfileService.getPublicProfile(username));
    }

    /** Get current user's own profile */
    @GetMapping("/me/profile")
    public ResponseEntity<?> getMyProfile(HttpServletRequest request) {
        return ResponseEntity.ok(userProfileService.getPublicProfile(extractUsername(request)));
    }

    /** Update current user's profile */
    @PutMapping("/me/profile")
    public ResponseEntity<?> updateProfile(@RequestBody Map<String, String> body, HttpServletRequest request) {
        String username = extractUsername(request);
        com.example.websocket.model.UserStatus status = null;
        if (body.get("status") != null) {
            try { status = com.example.websocket.model.UserStatus.valueOf(body.get("status").toUpperCase()); } catch (Exception ignored) {}
        }
        return ResponseEntity.ok(userProfileService.updateProfile(username, body.get("displayName"), body.get("bio"), body.get("avatarUrl"), body.get("phone"), status));
    }

    /** Upload avatar to Cloudinary */
    @PostMapping("/me/avatar")
    public ResponseEntity<?> uploadAvatar(@RequestParam("file") MultipartFile file, HttpServletRequest request) {
        String username = extractUsername(request);
        try {
            FileStorageService.UploadResult result = fileStorageService.storeFile(file);
            userProfileService.updateAvatarUrl(username, result.fileUrl());
            return ResponseEntity.ok(Map.of("avatarUrl", result.fileUrl(), "message", "Avatar updated."));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Avatar upload failed: " + e.getMessage()));
        }
    }

    /** Search users by username or displayName */
    @GetMapping("/search")
    public ResponseEntity<List<Map<String, Object>>> searchUsers(@RequestParam String q) {
        if (q == null || q.isBlank()) return ResponseEntity.badRequest().build();
        return ResponseEntity.ok(userProfileService.searchUsers(q));
    }

    private String extractUsername(HttpServletRequest request) {
        String token = jwtService.extractToken(request);
        return jwtUtil.extractUsername(token);
    }
}
