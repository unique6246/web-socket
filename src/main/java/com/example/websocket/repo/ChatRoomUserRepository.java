package com.example.websocket.repo;

import com.example.websocket.model.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ChatRoomUserRepository extends JpaRepository<ChatRoomUser, Long> {

    @Query("SELECT cu.chatRoom.roomName FROM ChatRoomUser cu WHERE cu.user.username = :username")
    List<String> findRoomNamesByUsername(@Param("username") String username);
}
