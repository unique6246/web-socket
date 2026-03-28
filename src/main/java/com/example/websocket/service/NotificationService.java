package com.example.websocket.service;

import com.example.websocket.config.SocketConnectionHandler;
import com.example.websocket.model.*;
import com.example.websocket.repo.NotificationRepository;
import com.example.websocket.repo.UserRepository;
import jakarta.transaction.Transactional;
import org.json.JSONObject;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final SocketConnectionHandler socketHandler;

    public NotificationService(NotificationRepository notificationRepository,
                                UserRepository userRepository,
                                @Lazy SocketConnectionHandler socketHandler) {
        this.notificationRepository = notificationRepository;
        this.userRepository = userRepository;
        this.socketHandler = socketHandler;
    }

    @Transactional
    public Notification createNotification(User recipient, NotificationType type, Long referenceId, String content) {
        Notification n = new Notification();
        n.setRecipient(recipient);
        n.setType(type);
        n.setReferenceId(referenceId);
        n.setContent(content);
        Notification saved = notificationRepository.save(n);

        // ── Push to the recipient in real time ──────────────────────────
        try {
            long unread = notificationRepository.countByRecipientAndIsReadFalse(recipient);
            JSONObject push = new JSONObject();
            push.put("eventType",       "NOTIFICATION");
            push.put("notificationId",  saved.getId());
            push.put("notifType",       type.name());
            push.put("content",         content);
            push.put("unreadCount",     unread);
            push.put("recipientUsername", recipient.getUsername());
            socketHandler.pushToUser(recipient.getUsername(), push);
        } catch (Exception e) {
            // Non-fatal — notification is already saved
        }

        return saved;
    }

    /**
     * Returns a Page of safe DTOs — no raw Notification entities with lazy User back-refs.
     * Called inside a @Transactional context (NotificationController is annotated).
     */
    @Transactional
    public Page<Map<String, Object>> getNotifications(String username, int page, int size) {
        User user = getUserOrThrow(username);
        Page<Notification> raw = notificationRepository.findByRecipientOrderByCreatedAtDesc(
                user, PageRequest.of(page, size));
        List<Map<String, Object>> dtos = raw.getContent().stream()
                .map(this::toDto)
                .collect(Collectors.toList());
        return new PageImpl<>(dtos, raw.getPageable(), raw.getTotalElements());
    }

    public long getUnreadCount(String username) {
        User user = getUserOrThrow(username);
        return notificationRepository.countByRecipientAndIsReadFalse(user);
    }

    @Transactional
    public void markRead(Long notificationId, String username) {
        Notification n = notificationRepository.findById(notificationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Notification not found"));
        if (!n.getRecipient().getUsername().equals(username))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not your notification.");
        n.setRead(true);
        notificationRepository.save(n);
    }

    @Transactional
    public void markAllRead(String username) {
        User user = getUserOrThrow(username);
        notificationRepository.markAllReadByRecipient(user);
    }

    // ── DTO helper ────────────────────────────────────────────────────────────

    private Map<String, Object> toDto(Notification n) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",            n.getId());
        m.put("type",          n.getType() != null ? n.getType().name() : null);
        m.put("content",       n.getContent());
        m.put("referenceId",   n.getReferenceId());
        m.put("referenceType", n.getReferenceType());
        m.put("isRead",        n.isRead());
        m.put("createdAt",     n.getCreatedAt() != null ? n.getCreatedAt().toString() : null);
        // Only expose the recipient username — never the full lazy User object
        m.put("recipientUsername", n.getRecipient() != null ? n.getRecipient().getUsername() : null);
        return m;
    }

    private User getUserOrThrow(String username) {
        User u = userRepository.findByUsername(username);
        if (u == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found");
        return u;
    }
}
