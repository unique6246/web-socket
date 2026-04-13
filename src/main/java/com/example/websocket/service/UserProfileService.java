package com.example.websocket.service;

import com.example.websocket.config.SocketConnectionHandler;
import com.example.websocket.model.User;
import com.example.websocket.model.UserStatus;
import com.example.websocket.repo.UserRepository;
import jakarta.transaction.Transactional;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class UserProfileService {

    private final UserRepository userRepository;
    private final SocketConnectionHandler socketHandler;

    public UserProfileService(UserRepository userRepository,
                               @Lazy SocketConnectionHandler socketHandler) {
        this.userRepository = userRepository;
        this.socketHandler = socketHandler;
    }

    public Map<String, Object> getPublicProfile(String username) {
        User u = getUserOrThrow(username);
        return buildPublicProfile(u);
    }

    @Transactional
    public Map<String, Object> updateProfile(String username, String displayName, String bio, String avatarUrl, String phone, UserStatus status) {
        User u = getUserOrThrow(username);
        if (displayName != null && !displayName.isBlank()) u.setDisplayName(displayName.trim());
        if (bio != null) u.setBio(bio.trim());
        if (avatarUrl != null && !avatarUrl.isBlank()) u.setAvatarUrl(avatarUrl.trim());
        if (phone != null) u.setPhone(phone.trim());
        if (status != null) {
            u.setStatus(status);
            // If user explicitly picks ONLINE → clear the override so auto-status works normally.
            // If user picks AWAY / DND / OFFLINE → set the override so WS connect/disconnect won't overwrite it.
            u.setManualStatusOverride(status != UserStatus.ONLINE);
        }
        User saved = userRepository.save(u);

        // Broadcast presence change globally so all connected users see the update
        if (status != null) {
            try {
                socketHandler.broadcastPresenceGlobally(username, status.name());
            } catch (Exception e) {
                // Non-fatal
            }
        }

        return buildPublicProfile(saved);
    }

    @Transactional
    public Map<String, Object> updateAvatarUrl(String username, String avatarUrl) {
        User u = getUserOrThrow(username);
        u.setAvatarUrl(avatarUrl);
        userRepository.save(u);
        return Map.of("avatarUrl", avatarUrl, "message", "Avatar updated.");
    }

    /**
     * Called automatically on WS connect and login.
     * Skips if the user has a manually-set status override (AWAY / DND / Appear Offline).
     */
    @Transactional
    public void setOnline(String username) {
        User u = userRepository.findByUsername(username);
        if (u == null) return;
        if (u.isManualStatusOverride()) return;   // respect user's explicit choice
        u.setStatus(UserStatus.ONLINE);
        u.setLastSeen(LocalDateTime.now());
        userRepository.save(u);
    }

    /**
     * Called automatically on WS disconnect.
     * Skips if the user has a manually-set status override (AWAY / DND / Appear Offline).
     */
    @Transactional
    public void setOffline(String username) {
        User u = userRepository.findByUsername(username);
        if (u == null) return;
        if (u.isManualStatusOverride()) return;   // respect user's explicit choice on WS disconnect
        u.setStatus(UserStatus.OFFLINE);
        u.setLastSeen(LocalDateTime.now());
        userRepository.save(u);
    }

    /**
     * Called on explicit logout. Always sets OFFLINE and clears any manual override
     * so the user starts fresh next time they log in.
     */
    @Transactional
    public void forceOffline(String username) {
        User u = userRepository.findByUsername(username);
        if (u == null) return;
        u.setManualStatusOverride(false);
        u.setStatus(UserStatus.OFFLINE);
        u.setLastSeen(LocalDateTime.now());
        userRepository.save(u);
    }

    public List<Map<String, Object>> searchUsers(String query) {
        String q = query.trim().toLowerCase();
        List<User> byUsername = userRepository.findByUsernameContainingIgnoreCase(q);
        List<User> byDisplay  = userRepository.findByDisplayNameContainingIgnoreCase(q);
        return Stream.concat(byUsername.stream(), byDisplay.stream())
                .distinct()
                .map(this::buildPublicProfile)
                .collect(Collectors.toList());
    }

    private Map<String, Object> buildPublicProfile(User u) {
        return Map.of(
            "username",      u.getUsername(),
            "displayName",   u.getDisplayName() != null ? u.getDisplayName() : u.getUsername(),
            "bio",           u.getBio() != null ? u.getBio() : "",
            "avatarUrl",     u.getAvatarUrl() != null ? u.getAvatarUrl() : "",
            "status",        u.getStatus() != null ? u.getStatus().name() : "OFFLINE",
            "lastSeen",      u.getLastSeen() != null ? u.getLastSeen().toString() : "",
            "emailVerified", u.isEmailVerified(),
            "createdAt",     u.getCreatedAt() != null ? u.getCreatedAt().toString() : ""
        );
    }

    private User getUserOrThrow(String username) {
        User u = userRepository.findByUsername(username);
        if (u == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found");
        return u;
    }
}
