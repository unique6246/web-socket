package com.example.websocket.model;

import com.fasterxml.jackson.annotation.JsonBackReference;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import lombok.*;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "users", indexes = {
    @Index(name = "idx_user_email", columnList = "email"),
    @Index(name = "idx_user_username", columnList = "username")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class User implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String username;

    @Column(nullable = false)
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String password;

    @Column(unique = true)
    @Email
    private String email;

    @Column(length = 20)
    private String phone;

    /** Friendly display name shown in UI (defaults to username) */
    @Column(length = 80)
    private String displayName;

    /** Cloudinary URL for profile avatar */
    @Column(length = 500)
    private String avatarUrl;

    @Column(length = 300)
    private String bio;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserStatus status = UserStatus.ONLINE;

    /**
     * When true, the user explicitly chose their status from the profile page
     * (e.g. AWAY, DND, Appear Offline).  Automatic status changes from
     * WS connect/disconnect and login/logout will NOT override this.
     * Reset to false when the user explicitly sets ONLINE from the profile page.
     */
    @Column(nullable = false)
    private boolean manualStatusOverride = false;

    /** Last time the user was seen online */
    private LocalDateTime lastSeen;

    @Column(nullable = false)
    private boolean emailVerified = false;

    /** How many consecutive failed login attempts since last success */
    @Column(nullable = false)
    private int failedLoginAttempts = 0;

    /** When set, account is locked until this time (persisted across restarts) */
    private LocalDateTime lockedUntil;

    /**
     * OAuth2 provider name (e.g. "google", "github").
     * Null for users who registered via username/password.
     */
    @Column(length = 30)
    private String provider;

    /**
     * The unique user-ID returned by the OAuth2 provider (subject claim).
     */
    @Column(length = 255)
    private String providerUserId;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (displayName == null || displayName.isBlank()) displayName = username;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(name = "user_roles",
               joinColumns = @JoinColumn(name = "user_id"),
               inverseJoinColumns = @JoinColumn(name = "role_id"))
    private Set<Role> roles = new HashSet<>();

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
    @JsonBackReference
    private Set<ChatRoomUser> chatRoomUsers = new HashSet<>();
}
