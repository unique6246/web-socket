package com.example.websocket.model;

import com.fasterxml.jackson.annotation.JsonBackReference;
import jakarta.persistence.*;
import lombok.*;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "messages", indexes = {
    @Index(name = "idx_msg_chatroom", columnList = "chatroom_id"),
    @Index(name = "idx_msg_sender", columnList = "sender"),
    @Index(name = "idx_msg_timestamp", columnList = "timestamp")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Message implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(columnDefinition = "TEXT")
    private String content;

    private String sender;
    private LocalDateTime timestamp;

    // ── File attachment ──
    @Column(length = 500)
    private String fileUrl;
    @Column(length = 100)
    private String fileType;
    @Column(length = 255)
    private String fileName;

    // ── Message type ──
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MessageType messageType = MessageType.TEXT;

    // ── Editing ──
    private LocalDateTime editedAt;

    @Column(nullable = false)
    private boolean isEdited = false;

    // ── Soft-delete ──
    private LocalDateTime deletedAt;

    @Column(nullable = false)
    private boolean isDeleted = false;

    // ── Pin ──
    @Column(nullable = false)
    private boolean isPinned = false;

    // ── Threading / Reply ──
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reply_to_message_id")
    private Message replyTo;

    // ── Reactions ──
    @OneToMany(mappedBy = "message", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<MessageReaction> reactions = new ArrayList<>();

    @ManyToOne
    @JoinColumn(name = "chatroom_id")
    @JsonBackReference
    private ChatRoom chatRoom;
}
