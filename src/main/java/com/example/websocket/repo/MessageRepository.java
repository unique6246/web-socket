package com.example.websocket.repo;

import com.example.websocket.model.*;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MessageRepository extends JpaRepository<Message, Long> {

    @Query("SELECT m FROM Message m WHERE m.chatRoom = :room AND m.isDeleted = false ORDER BY m.timestamp ASC")
    List<Message> findMessagesByChatRoom(@Param("room") ChatRoom chatRoom);

    @Query("SELECT m FROM Message m WHERE m.chatRoom = :room AND m.isDeleted = false AND m.id < :beforeId ORDER BY m.timestamp DESC")
    List<Message> findByChatRoomBeforeId(@Param("room") ChatRoom room, @Param("beforeId") Long beforeId, Pageable pageable);

    @Query("SELECT m FROM Message m WHERE m.chatRoom = :room AND m.isPinned = true AND m.isDeleted = false ORDER BY m.timestamp DESC")
    List<Message> findPinnedByRoom(@Param("room") ChatRoom room);

    @Query("SELECT m FROM Message m WHERE m.chatRoom = :room AND m.isDeleted = false AND LOWER(m.content) LIKE LOWER(CONCAT('%', :query, '%')) ORDER BY m.timestamp DESC")
    List<Message> searchInRoom(@Param("room") ChatRoom room, @Param("query") String query, Pageable pageable);
}
