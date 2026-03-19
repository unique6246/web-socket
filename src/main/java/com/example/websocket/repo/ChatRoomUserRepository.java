package com.example.websocket.repo;

import com.example.websocket.model.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ChatRoomUserRepository extends JpaRepository<ChatRoomUser, Long> {

    @Query("SELECT cu.chatRoom.roomName FROM ChatRoomUser cu WHERE cu.user.username = :username")
    List<String> findRoomNamesByUsername(@Param("username") String username);

    /** Find a DM room that contains exactly these two usernames */
    @Query("SELECT cu.chatRoom FROM ChatRoomUser cu " +
           "WHERE cu.chatRoom.type = 'DM' " +
           "AND cu.chatRoom.id IN (" +
           "  SELECT cu2.chatRoom.id FROM ChatRoomUser cu2 WHERE cu2.user.username = :u1" +
           ") " +
           "AND cu.chatRoom.id IN (" +
           "  SELECT cu3.chatRoom.id FROM ChatRoomUser cu3 WHERE cu3.user.username = :u2" +
           ")")
    Optional<ChatRoom> findDmRoom(@Param("u1") String u1, @Param("u2") String u2);
}
