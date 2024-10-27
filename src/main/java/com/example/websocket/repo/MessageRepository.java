package com.example.websocket.repo;

import com.example.websocket.model.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MessageRepository extends JpaRepository<Message, Long> {
    List<Message> findMessagesByChatRoom(ChatRoom chatRoom);

}
