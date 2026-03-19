package com.example.websocket.controller;

import com.example.websocket.service.ChatRoomService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/moderator")
@PreAuthorize("hasAnyRole('ADMIN', 'MODERATOR')")
public class ModeratorController {

    private final ChatRoomService chatRoomService;

    public ModeratorController(ChatRoomService chatRoomService) {
        this.chatRoomService = chatRoomService;
    }

    /**
     * Delete a specific message from a room. Requires ADMIN or MODERATOR role.
     */
    @DeleteMapping("/rooms/{roomName}/messages/{messageId}")
    public ResponseEntity<?> deleteMessage(@PathVariable String roomName,
                                            @PathVariable Long messageId) {
        chatRoomService.deleteMessage(messageId, roomName);
        return ResponseEntity.ok(Map.of("message", "Message deleted successfully"));
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
