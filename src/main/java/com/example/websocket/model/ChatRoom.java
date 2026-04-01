package com.example.websocket.model;

import com.fasterxml.jackson.annotation.JsonManagedReference;
import jakarta.persistence.*;
import lombok.*;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.*;

@Entity
@Table(name = "chat_rooms")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ChatRoom implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String roomName;

    /** "DM" for direct messages, "GROUP" for group chats */
    @Column(nullable = false)
    private String type = "GROUP";

    @Column(length = 500)
    private String description;

    /** Cloudinary URL for group avatar */
    @Column(length = 500)
    private String avatarUrl;

    /** true = invite-only / private group */
    @Column(nullable = false)
    private boolean isPrivate = false;

    /** Shareable invite code (UUID) */
    @Column(unique = true, length = 36)
    private String inviteCode;

    /** Username of the user who created the room */
    private String createdBy;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    @OneToMany(mappedBy = "chatRoom", cascade = CascadeType.PERSIST, orphanRemoval = true)
    @JsonManagedReference
    private Set<ChatRoomUser> chatRoomUsers = new HashSet<>();

    @OneToMany(mappedBy = "chatRoom", cascade = CascadeType.ALL, orphanRemoval = true)
    @JsonManagedReference
    private List<Message> messages = new ArrayList<>();
}
