package com.example.websocket.controller;

import com.example.websocket.JWT.JwtService;
import com.example.websocket.JWT.JwtUtil;
import com.example.websocket.config.SocketConnectionHandler;
import com.example.websocket.model.Message;
import com.example.websocket.service.MessageService;
import com.example.websocket.service.ReactionService;
import jakarta.servlet.http.HttpServletRequest;
import org.json.JSONObject;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/messages")
@PreAuthorize("isAuthenticated()")
public class MessageController {

    private final MessageService messageService;
    private final ReactionService reactionService;
    private final JwtService jwtService;
    private final JwtUtil jwtUtil;
    private final SocketConnectionHandler socketHandler;

    public MessageController(MessageService messageService, ReactionService reactionService,
                              JwtService jwtService, JwtUtil jwtUtil,
                              @Lazy SocketConnectionHandler socketHandler) {
        this.messageService = messageService;
        this.reactionService = reactionService;
        this.jwtService = jwtService;
        this.jwtUtil = jwtUtil;
        this.socketHandler = socketHandler;
    }

    /** Edit a message */
    @PatchMapping("/{id}")
    public ResponseEntity<?> editMessage(@PathVariable Long id,
                                          @RequestBody Map<String, String> body,
                                          HttpServletRequest request) {
        String username = extractUsername(request);
        String newContent = body.get("content");
        if (newContent == null || newContent.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "Content cannot be blank."));
        Message updated = messageService.editMessage(id, username, newContent.trim());

        // Broadcast real-time edit to all room members
        try {
            String roomName = updated.getChatRoom().getRoomName();
            JSONObject ws = new JSONObject();
            ws.put("eventType",  "MESSAGE_EDIT");
            ws.put("messageId",  updated.getId());
            ws.put("content",    updated.getContent());
            ws.put("roomName",   roomName);
            ws.put("sender",     username);
            ws.put("timestamp",  LocalDateTime.now().toString());
            socketHandler.broadcastJsonToRoom(roomName, ws);
        } catch (Exception ignored) {}

        return ResponseEntity.ok(Map.of(
            "id",       updated.getId(),
            "content",  updated.getContent(),
            "editedAt", updated.getEditedAt().toString(),
            "isEdited", true
        ));
    }

    /** Soft-delete a message */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteMessage(@PathVariable Long id, HttpServletRequest request) {
        String username = extractUsername(request);
        Message msg = messageService.getMessageForBroadcast(id);
        String roomName = msg != null ? msg.getChatRoom().getRoomName() : null;
        messageService.deleteMessage(id, username);

        // Broadcast real-time delete to all room members
        if (roomName != null) {
            try {
                JSONObject ws = new JSONObject();
                ws.put("eventType", "MESSAGE_DELETE");
                ws.put("messageId", id);
                ws.put("roomName",  roomName);
                ws.put("sender",    username);
                ws.put("timestamp", LocalDateTime.now().toString());
                socketHandler.broadcastJsonToRoom(roomName, ws);
            } catch (Exception ignored) {}
        }

        return ResponseEntity.ok(Map.of("message", "Message deleted.", "id", id));
    }

    /** Add emoji reaction */
    @PostMapping("/{id}/react")
    public ResponseEntity<?> addReaction(@PathVariable Long id,
                                          @RequestBody Map<String, String> body,
                                          HttpServletRequest request) {
        String username = extractUsername(request);
        String emoji = body.get("emoji");
        if (emoji == null || emoji.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "Emoji is required."));
        String roomName = reactionService.addReactionAndGetRoom(id, username, emoji.trim());

        // Broadcast real-time reaction to all room members
        if (roomName != null) {
            try {
                JSONObject ws = new JSONObject();
                ws.put("eventType",    "REACTION");
                ws.put("messageId",    id);
                ws.put("reactionEmoji", emoji.trim());
                ws.put("roomName",     roomName);
                ws.put("sender",       username);
                ws.put("timestamp",    LocalDateTime.now().toString());
                socketHandler.broadcastJsonToRoom(roomName, ws);
            } catch (Exception ignored) {}
        }

        return ResponseEntity.ok(Map.of("message", "Reaction added."));
    }

    /** Remove emoji reaction */
    @DeleteMapping("/{id}/react/{emoji}")
    public ResponseEntity<?> removeReaction(@PathVariable Long id,
                                             @PathVariable String emoji,
                                             HttpServletRequest request) {
        String username = extractUsername(request);
        String roomName = reactionService.removeReactionAndGetRoom(id, username, emoji);

        // Broadcast real-time reaction removal
        if (roomName != null) {
            try {
                JSONObject ws = new JSONObject();
                ws.put("eventType",    "REACTION");
                ws.put("messageId",    id);
                ws.put("reactionEmoji", emoji);
                ws.put("roomName",     roomName);
                ws.put("sender",       username);
                ws.put("timestamp",    LocalDateTime.now().toString());
                socketHandler.broadcastJsonToRoom(roomName, ws);
            } catch (Exception ignored) {}
        }

        return ResponseEntity.ok(Map.of("message", "Reaction removed."));
    }

    /** Get reaction summary for a message */
    @GetMapping("/{id}/reactions")
    public ResponseEntity<?> getReactions(@PathVariable Long id) {
        return ResponseEntity.ok(reactionService.getReactionSummary(id));
    }

    /** Pin/unpin a message */
    @PostMapping("/{id}/pin")
    public ResponseEntity<?> pinMessage(@PathVariable Long id, HttpServletRequest request) {
        String username = extractUsername(request);
        Message msg = messageService.pinMessage(id, username);

        // Broadcast real-time pin toggle
        try {
            String roomName = msg.getChatRoom().getRoomName();
            JSONObject ws = new JSONObject();
            ws.put("eventType", "PIN");
            ws.put("messageId", msg.getId());
            ws.put("isPinned",  msg.isPinned());
            ws.put("roomName",  roomName);
            ws.put("sender",    username);
            ws.put("timestamp", LocalDateTime.now().toString());
            socketHandler.broadcastJsonToRoom(roomName, ws);
        } catch (Exception ignored) {}

        return ResponseEntity.ok(Map.of("id", msg.getId(), "isPinned", msg.isPinned()));
    }

    /** Paginated messages for a room — returns safe DTOs */
    @GetMapping("/room/{roomName}")
    @Transactional(readOnly = true)
    public ResponseEntity<List<Map<String, Object>>> getMessages(
            @PathVariable String roomName,
            @RequestParam(required = false) Long before,
            @RequestParam(defaultValue = "50") int limit) {
        List<Message> messages = messageService.getPagedMessages(roomName, before, Math.min(limit, 100));
        return ResponseEntity.ok(messages.stream().map(this::toMessageDto).collect(Collectors.toList()));
    }

    /** Pinned messages for a room — safe DTOs */
    @GetMapping("/room/{roomName}/pinned")
    @Transactional(readOnly = true)
    public ResponseEntity<List<Map<String, Object>>> getPinnedMessages(@PathVariable String roomName) {
        return ResponseEntity.ok(messageService.getPinnedMessages(roomName)
                .stream().map(this::toMessageDto).collect(Collectors.toList()));
    }

    /** Search messages in a room — safe DTOs */
    @GetMapping("/room/{roomName}/search")
    @Transactional(readOnly = true)
    public ResponseEntity<List<Map<String, Object>>> searchMessages(
            @PathVariable String roomName,
            @RequestParam String q,
            @RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(messageService.searchMessages(roomName, q, Math.min(limit, 50))
                .stream().map(this::toMessageDto).collect(Collectors.toList()));
    }

    // ── DTO helper ───────────────────────────────────────────────────────────

    /**
     * Converts a Message entity to a flat Map safe for JSON serialization.
     */
    private Map<String, Object> toMessageDto(Message m) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id",        m.getId());
        dto.put("sender",    m.getSender());
        dto.put("content",   m.isDeleted() ? null : m.getContent());
        dto.put("fileUrl",   m.getFileUrl());
        dto.put("fileName",  m.getFileName());
        dto.put("fileType",  m.getFileType());
        dto.put("messageType", m.getMessageType() != null ? m.getMessageType().name() : "TEXT");
        dto.put("timestamp", m.getTimestamp() != null ? m.getTimestamp().toString() : null);
        dto.put("isEdited",  m.isEdited());
        dto.put("editedAt",  m.getEditedAt() != null ? m.getEditedAt().toString() : null);
        dto.put("isDeleted", m.isDeleted());
        dto.put("isPinned",  m.isPinned());

        // Reply-to: only include safe scalar fields — avoid loading the full chain
        if (m.getReplyTo() != null) {
            Message rt = m.getReplyTo();
            Map<String, Object> reply = new LinkedHashMap<>();
            reply.put("id",      rt.getId());
            reply.put("sender",  rt.getSender());
            reply.put("content", rt.isDeleted() ? null : rt.getContent());
            dto.put("replyTo", reply);
        } else {
            dto.put("replyTo", null);
        }

        // Reactions: aggregate emoji→count map from the lazy collection
        try {
            Map<String, Long> reactionCounts = new LinkedHashMap<>();
            if (m.getReactions() != null) {
                for (var r : m.getReactions()) {
                    reactionCounts.merge(r.getEmoji(), 1L, Long::sum);
                }
            }
            dto.put("reactions", reactionCounts);
        } catch (Exception e) {
            dto.put("reactions", Map.of());
        }

        return dto;
    }

    private String extractUsername(HttpServletRequest request) {
        return jwtUtil.extractUsername(jwtService.extractToken(request));
    }
}
