package com.example.websocket.model;

import com.fasterxml.jackson.annotation.JsonBackReference;
import jakarta.persistence.*;
import lombok.*;

import java.io.Serializable;
import java.time.LocalDateTime;

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Message implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String content;
    private String sender;
    private LocalDateTime timestamp;

    //files
    private String fileUrl;
    private String fileType;
    private String fileName;

    @ManyToOne
    @JoinColumn(name = "chatroom_id")
    @JsonBackReference
    private ChatRoom chatRoom;
}
