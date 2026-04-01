package com.example.websocket.service;

import com.example.websocket.model.MessageReaction;
import com.example.websocket.repo.MessageReactionRepository;
import com.example.websocket.repo.MessageRepository;
import jakarta.transaction.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

@Service
public class ReactionService {

    private final MessageReactionRepository reactionRepository;
    private final MessageRepository messageRepository;

    public ReactionService(MessageReactionRepository reactionRepository,
                           MessageRepository messageRepository) {
        this.reactionRepository = reactionRepository;
        this.messageRepository = messageRepository;
    }

    @Transactional
    public void addReaction(Long messageId, String username, String emoji) {
        addReactionAndGetRoom(messageId, username, emoji);
    }

    /**
     * Adds a reaction and returns the room name for broadcasting.
     */
    @Transactional
    public String addReactionAndGetRoom(Long messageId, String username, String emoji) {
        var msg = messageRepository.findById(messageId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Message not found"));
        if (msg.isDeleted()) throw new ResponseStatusException(HttpStatus.GONE, "Message deleted.");
        // Idempotent — if already reacted, do nothing
        if (reactionRepository.findByMessageIdAndUsernameAndEmoji(messageId, username, emoji).isPresent()) {
            return msg.getChatRoom() != null ? msg.getChatRoom().getRoomName() : null;
        }
        MessageReaction reaction = new MessageReaction();
        reaction.setMessage(msg);
        reaction.setUsername(username);
        reaction.setEmoji(emoji);
        reactionRepository.save(reaction);
        return msg.getChatRoom() != null ? msg.getChatRoom().getRoomName() : null;
    }

    @Transactional
    public void removeReaction(Long messageId, String username, String emoji) {
        removeReactionAndGetRoom(messageId, username, emoji);
    }

    /**
     * Removes a reaction and returns the room name for broadcasting.
     */
    @Transactional
    public String removeReactionAndGetRoom(Long messageId, String username, String emoji) {
        var msg = messageRepository.findById(messageId).orElse(null);
        reactionRepository.deleteByMessageIdAndUsernameAndEmoji(messageId, username, emoji);
        return msg != null && msg.getChatRoom() != null ? msg.getChatRoom().getRoomName() : null;
    }

    public Map<String, Object> getReactionSummary(Long messageId) {
        List<Object[]> rows = reactionRepository.countByMessageIdGroupByEmoji(messageId);
        List<MessageReaction> rawReactions = reactionRepository.findByMessageId(messageId);

        // Aggregate counts
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Object[] row : rows) {
            counts.put((String) row[0], (Long) row[1]);
        }

        // Safe DTO list — never serialize the lazy Message back-reference
        List<Map<String, Object>> reactions = rawReactions.stream()
                .map(r -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id",        r.getId());
                    m.put("emoji",     r.getEmoji());
                    m.put("username",  r.getUsername());
                    m.put("messageId", messageId);
                    return m;
                })
                .collect(java.util.stream.Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("counts",    counts);
        result.put("reactions", reactions);
        return result;
    }
}
