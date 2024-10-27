package com.example.websocket.repo;

import com.example.websocket.model.ChatRoomUser;
import com.example.websocket.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Set;

public interface ChatRoomUserRepository extends JpaRepository<ChatRoomUser, Long> {

    @Query("SELECT cu.user FROM ChatRoomUser cu WHERE cu.chatRoom.roomName = :roomName")
    Set<User> findUsersByRoomName(@Param("roomName") String roomName);
}
