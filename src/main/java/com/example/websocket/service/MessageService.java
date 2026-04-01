package com.example.websocket.service;

import com.example.websocket.model.*;
import com.example.websocket.repo.*;
import jakarta.transaction.Transactional;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.*;

@Service
public class MessageService {

    private final MessageRepository messageRepository;
    private final ChatRoomRepository chatRoomRepository;
    private final UserRepository userRepository;

    public MessageService(MessageRepository messageRepository,
                          ChatRoomRepository chatRoomRepository,
                          UserRepository userRepository) {
        this.messageRepository = messageRepository;
        this.chatRoomRepository = chatRoomRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public Message editMessage(Long messageId, String editorUsername, String newContent) {
        Message msg = getMessageOrThrow(messageId);
        if (msg.isDeleted()) throw new ResponseStatusException(HttpStatus.GONE, "Message has been deleted.");
        if (!msg.getSender().equals(editorUsername)) {
            // Check if editor is room admin
            boolean isAdmin = msg.getChatRoom().getChatRoomUsers().stream()
                    .anyMatch(cru -> cru.getUser().getUsername().equals(editorUsername) && cru.isGroupAdmin());
            if (!isAdmin) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You can only edit your own messages.");
        }
        msg.setContent(newContent);
        msg.setEdited(true);
        msg.setEditedAt(LocalDateTime.now());
        return messageRepository.save(msg);
    }

    @Transactional
    public void deleteMessage(Long messageId, String deleterUsername) {
        Message msg = getMessageOrThrow(messageId);
        if (msg.isDeleted()) return;
        if (!msg.getSender().equals(deleterUsername)) {
            boolean isAdmin = msg.getChatRoom().getChatRoomUsers().stream()
                    .anyMatch(cru -> cru.getUser().getUsername().equals(deleterUsername) && cru.isGroupAdmin());
            if (!isAdmin) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You can only delete your own messages.");
        }
        msg.setDeleted(true);
        msg.setDeletedAt(LocalDateTime.now());
        msg.setContent(null);
        msg.setFileUrl(null);
        messageRepository.save(msg);
    }

    @Transactional
    public Message pinMessage(Long messageId, String actorUsername) {
        Message msg = getMessageOrThrow(messageId);
        String roomType = msg.getChatRoom().getType();

        // In DM rooms, any participant can pin.
        // In GROUP rooms, the group admin or the message sender can pin.
        if (!"DM".equals(roomType)) {
            boolean isAdmin = msg.getChatRoom().getChatRoomUsers().stream()
                    .anyMatch(cru -> cru.getUser().getUsername().equals(actorUsername) && cru.isGroupAdmin());
            boolean isSender = msg.getSender().equals(actorUsername);
            if (!isAdmin && !isSender) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only admins or the message sender can pin messages.");
            }
        }

        msg.setPinned(!msg.isPinned());
        return messageRepository.save(msg);
    }

    public List<Message> getPagedMessages(String roomName, Long beforeId, int limit) {
        ChatRoom room = chatRoomRepository.findByRoomName(roomName)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Room not found"));
        if (beforeId == null) {
            List<Message> all = messageRepository.findMessagesByChatRoom(room);
            int from = Math.max(0, all.size() - limit);
            return all.subList(from, all.size());
        }
        List<Message> page = messageRepository.findByChatRoomBeforeId(room, beforeId, PageRequest.of(0, limit));
        Collections.reverse(page);
        return page;
    }

    public List<Message> getPinnedMessages(String roomName) {
        ChatRoom room = chatRoomRepository.findByRoomName(roomName)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Room not found"));
        return messageRepository.findPinnedByRoom(room);
    }

    public List<Message> searchMessages(String roomName, String query, int limit) {
        ChatRoom room = chatRoomRepository.findByRoomName(roomName)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Room not found"));
        return messageRepository.searchInRoom(room, query, PageRequest.of(0, limit));
    }

    private Message getMessageOrThrow(Long id) {
        return messageRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Message not found"));
    }

    /**
     * Loads a message by ID (with its chatRoom) for broadcast purposes.
     * Returns null if not found instead of throwing.
     */
    public Message getMessageForBroadcast(Long id) {
        return messageRepository.findById(id).orElse(null);
    }
}
