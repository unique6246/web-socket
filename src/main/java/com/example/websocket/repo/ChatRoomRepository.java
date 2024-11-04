package com.example.websocket.repo;

import com.example.websocket.model.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.*;
@Repository
public interface ChatRoomRepository extends JpaRepository<ChatRoom, Long> {
    Optional<ChatRoom> findByRoomName(String roomName);

    List<ChatRoom> findByRoomNameContainingIgnoreCase(String query);
}

