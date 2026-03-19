package com.example.websocket.JWT;

import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory token blacklist for revocation on logout.
 * Stores the token JTI (or full token) along with its expiry time.
 * Expired entries are lazily cleaned up to prevent unbounded memory growth.
 */
@Service
public class TokenBlacklistService {

    // token -> expiry time
    private final Map<String, Date> blacklist = new ConcurrentHashMap<>();

    public void blacklist(String token, Date expiry) {
        if (token != null && expiry != null) {
            cleanupExpired();
            blacklist.put(token, expiry);
        }
    }

    public boolean isBlacklisted(String token) {
        if (token == null) return false;
        Date expiry = blacklist.get(token);
        if (expiry == null) return false;
        // If token is already expired remove it from blacklist (no need to keep)
        if (expiry.before(new Date())) {
            blacklist.remove(token);
            return false;
        }
        return true;
    }

    /** Remove tokens whose expiry has already passed */
    private void cleanupExpired() {
        Date now = new Date();
        blacklist.entrySet().removeIf(entry -> entry.getValue().before(now));
    }
}
