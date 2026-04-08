package com.example.websocket.controller;

import com.example.websocket.config.SocketConnectionHandler;
import com.example.websocket.model.Message;
import com.example.websocket.service.ChatRoomService;
import com.example.websocket.service.MessageService;
import org.json.JSONObject;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;

@RestController
@RequestMapping("/api/moderator")
@PreAuthorize("hasAnyRole('ADMIN', 'MODERATOR')")
public class ModeratorController {

    private final MessageService messageService;
    private final ChatRoomService chatRoomService;
    private final SocketConnectionHandler socketHandler;

    public ModeratorController(MessageService messageService,
                                ChatRoomService chatRoomService,
                                @Lazy SocketConnectionHandler socketHandler) {
        this.messageService = messageService;
        this.chatRoomService = chatRoomService;
        this.socketHandler = socketHandler;
    }

    /**
     * Soft-delete a message from a room (consistent with user self-delete).
     * Broadcasts MESSAGE_DELETE to the room so clients update in real time.
     */
    @DeleteMapping("/rooms/{roomName}/messages/{messageId}")
    public ResponseEntity<?> deleteMessage(@PathVariable String roomName,
                                            @PathVariable Long messageId,
                                            @AuthenticationPrincipal UserDetails moderator) {
        String actorUsername = moderator != null ? moderator.getUsername() : "moderator";
        // Soft-delete via MessageService (same path as user self-delete)
        Message msg = messageService.getMessageForBroadcast(messageId);
        messageService.deleteMessage(messageId, actorUsername);

        // Broadcast real-time delete so connected clients see the change instantly
        if (msg != null) {
            try {
                JSONObject ws = new JSONObject();
                ws.put("eventType", "MESSAGE_DELETE");
                ws.put("messageId", messageId);
                ws.put("roomName",  roomName);
                ws.put("sender",    actorUsername);
                ws.put("timestamp", LocalDateTime.now().toString());
                socketHandler.broadcastJsonToRoom(roomName, ws);
            } catch (Exception ignored) {}
        }
        return ResponseEntity.ok(Map.of("message", "Message deleted.", "id", messageId));
    }

    /**
     * Remove (kick) a user from a room. Requires ADMIN or MODERATOR role.
     */
    @DeleteMapping("/rooms/{roomName}/kick/{username}")
    public ResponseEntity<?> kickUser(@PathVariable String roomName,
                                       @PathVariable String username) {
        chatRoomService.removeUserFromRoom(username, roomName);
        return ResponseEntity.ok(Map.of("message", "User " + username + " removed from room " + roomName));
    }
}
