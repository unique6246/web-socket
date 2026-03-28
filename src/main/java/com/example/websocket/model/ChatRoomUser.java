package com.example.websocket.model;

import com.fasterxml.jackson.annotation.JsonBackReference;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "chat_room_user", uniqueConstraints = @UniqueConstraint(columnNames = {"chatroom_id", "user_id"}))
public class ChatRoomUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "chatroom_id", nullable = false)
    @JsonBackReference
    private ChatRoom chatRoom;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** Role in this room */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RoomRole roomRole = RoomRole.MEMBER;

    /** When user joined */
    @Column(nullable = false)
    private LocalDateTime joinedAt;

    /** ID of the last message the user has read in this room */
    private Long lastReadMessageId;

    /** Mute notifications for this room */
    @Column(nullable = false)
    private boolean isMuted = false;

    @PrePersist
    protected void onCreate() { joinedAt = LocalDateTime.now(); }

    // ── backward-compat helper (code that still uses isGroupAdmin()) ──────
    public boolean isGroupAdmin() {
        return roomRole == RoomRole.OWNER || roomRole == RoomRole.ADMIN;
    }

    public void setGroupAdmin(boolean admin) {
        this.roomRole = admin ? RoomRole.ADMIN : RoomRole.MEMBER;
    }
}
