package com.example.websocket.controller;

import com.example.websocket.JWT.JwtService;
import com.example.websocket.JWT.JwtUtil;
import com.example.websocket.model.Friendship;
import com.example.websocket.service.FriendshipService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/friends")
@PreAuthorize("isAuthenticated()")
public class FriendshipController {

    private final FriendshipService friendshipService;
    private final JwtService jwtService;
    private final JwtUtil jwtUtil;

    public FriendshipController(FriendshipService friendshipService, JwtService jwtService, JwtUtil jwtUtil) {
        this.friendshipService = friendshipService;
        this.jwtService = jwtService;
        this.jwtUtil = jwtUtil;
    }

    /** Send friend request */
    @PostMapping("/request/{username}")
    public ResponseEntity<?> sendRequest(@PathVariable String username, HttpServletRequest request) {
        try {
            Friendship f = friendshipService.sendFriendRequest(extractUsername(request), username);
            return ResponseEntity.ok(Map.of("message", "Friend request sent.", "id", f.getId()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** Accept friend request */
    @PostMapping("/accept/{requestId}")
    public ResponseEntity<?> acceptRequest(@PathVariable Long requestId, HttpServletRequest request) {
        try {
            Friendship f = friendshipService.acceptRequest(requestId, extractUsername(request));
            return ResponseEntity.ok(Map.of("message", "Friend request accepted.", "id", f.getId()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** Decline/cancel friend request */
    @DeleteMapping("/decline/{requestId}")
    public ResponseEntity<?> declineRequest(@PathVariable Long requestId, HttpServletRequest request) {
        friendshipService.declineRequest(requestId, extractUsername(request));
        return ResponseEntity.ok(Map.of("message", "Friend request declined."));
    }

    /** Block user */
    @PostMapping("/block/{username}")
    public ResponseEntity<?> blockUser(@PathVariable String username, HttpServletRequest request) {
        friendshipService.blockUser(extractUsername(request), username);
        return ResponseEntity.ok(Map.of("message", "User blocked."));
    }

    /** Get friends list */
    @GetMapping
    @Transactional(readOnly = true)
    public ResponseEntity<List<Map<String, Object>>> getFriends(HttpServletRequest request) {
        return ResponseEntity.ok(
            friendshipService.getFriends(extractUsername(request))
                .stream().map(this::toFriendshipDto).collect(Collectors.toList())
        );
    }

    /** Get pending friend requests */
    @GetMapping("/pending")
    @Transactional(readOnly = true)
    public ResponseEntity<List<Map<String, Object>>> getPending(HttpServletRequest request) {
        return ResponseEntity.ok(
            friendshipService.getPendingRequests(extractUsername(request))
                .stream().map(this::toFriendshipDto).collect(Collectors.toList())
        );
    }

    // ── DTO helper ───────────────────────────────────────────────────────────

    private Map<String, Object> toFriendshipDto(Friendship f) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id",     f.getId());
        dto.put("status", f.getStatus().name());
        dto.put("createdAt", f.getCreatedAt() != null ? f.getCreatedAt().toString() : null);
        // requester & addressee are LAZY — accessed while session is still open (@Transactional)
        dto.put("requester", Map.of(
            "username",    f.getRequester().getUsername(),
            "displayName", f.getRequester().getDisplayName() != null
                            ? f.getRequester().getDisplayName() : f.getRequester().getUsername(),
            "avatarUrl",   f.getRequester().getAvatarUrl() != null ? f.getRequester().getAvatarUrl() : ""
        ));
        dto.put("addressee", Map.of(
            "username",    f.getAddressee().getUsername(),
            "displayName", f.getAddressee().getDisplayName() != null
                            ? f.getAddressee().getDisplayName() : f.getAddressee().getUsername(),
            "avatarUrl",   f.getAddressee().getAvatarUrl() != null ? f.getAddressee().getAvatarUrl() : ""
        ));
        return dto;
    }

    private String extractUsername(HttpServletRequest request) {
        return jwtUtil.extractUsername(jwtService.extractToken(request));
    }
}
