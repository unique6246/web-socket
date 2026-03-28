package com.example.websocket.controller;

import com.example.websocket.JWT.JwtService;
import com.example.websocket.JWT.JwtUtil;
import com.example.websocket.service.NotificationService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
@RestController
@RequestMapping("/api/notifications")
@PreAuthorize("isAuthenticated()")
public class NotificationController {

    private final NotificationService notificationService;
    private final JwtService jwtService;
    private final JwtUtil jwtUtil;

    public NotificationController(NotificationService notificationService, JwtService jwtService, JwtUtil jwtUtil) {
        this.notificationService = notificationService;
        this.jwtService = jwtService;
        this.jwtUtil = jwtUtil;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public ResponseEntity<?> getNotifications(@RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "20") int limit,
                                               HttpServletRequest request) {
        String me = extractUsername(request);
        Page<Map<String, Object>> result = notificationService.getNotifications(me, page, Math.min(limit, 50));
        return ResponseEntity.ok(Map.of(
            "notifications",  result.getContent(),
            "totalPages",     result.getTotalPages(),
            "totalElements",  result.getTotalElements(),
            "page",           page
        ));
    }

    @GetMapping("/unread-count")
    public ResponseEntity<?> unreadCount(HttpServletRequest request) {
        String me = extractUsername(request);
        return ResponseEntity.ok(Map.of("unreadCount", notificationService.getUnreadCount(me)));
    }

    @PatchMapping("/{id}/read")
    public ResponseEntity<?> markRead(@PathVariable Long id, HttpServletRequest request) {
        String me = extractUsername(request);
        notificationService.markRead(id, me);
        return ResponseEntity.ok(Map.of("message", "Marked as read."));
    }

    @PatchMapping("/read-all")
    public ResponseEntity<?> markAllRead(HttpServletRequest request) {
        String me = extractUsername(request);
        notificationService.markAllRead(me);
        return ResponseEntity.ok(Map.of("message", "All notifications marked as read."));
    }

    private String extractUsername(HttpServletRequest request) {
        String token = jwtService.extractToken(request);
        return jwtUtil.extractUsername(token);
    }
}
