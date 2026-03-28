package com.example.websocket.repo;

import com.example.websocket.model.MessageReaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface MessageReactionRepository extends JpaRepository<MessageReaction, Long> {

    List<MessageReaction> findByMessageId(Long messageId);

    Optional<MessageReaction> findByMessageIdAndUsernameAndEmoji(Long messageId, String username, String emoji);

    void deleteByMessageIdAndUsernameAndEmoji(Long messageId, String username, String emoji);

    @Query("SELECT r.emoji, COUNT(r) FROM MessageReaction r WHERE r.message.id = :messageId GROUP BY r.emoji")
    List<Object[]> countByMessageIdGroupByEmoji(Long messageId);
}
