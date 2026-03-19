package com.example.websocket.config;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Simple in-memory brute-force protection for login.
 * Blocks an IP after MAX_ATTEMPTS failures within WINDOW_SECONDS.
 */
@Component
public class LoginRateLimiter {

    private static final int MAX_ATTEMPTS = 5;
    private static final long WINDOW_SECONDS = 300; // 5 minutes
    private static final long BLOCK_SECONDS  = 900; // 15 minutes after exceeding

    private record Attempt(AtomicInteger count, long windowStart, long blockedUntil) {}

    private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();

    public boolean isBlocked(String ip) {
        Attempt a = attempts.get(ip);
        if (a == null) return false;
        long now = Instant.now().getEpochSecond();
        if (a.blockedUntil() > now) return true;
        // Block expired — reset if window also expired
        if (now - a.windowStart() > WINDOW_SECONDS) {
            attempts.remove(ip);
        }
        return false;
    }

    public void recordFailure(String ip) {
        long now = Instant.now().getEpochSecond();
        attempts.compute(ip, (key, existing) -> {
            if (existing == null || now - existing.windowStart() > WINDOW_SECONDS) {
                return new Attempt(new AtomicInteger(1), now, 0L);
            }
            int count = existing.count().incrementAndGet();
            long blockedUntil = count >= MAX_ATTEMPTS ? now + BLOCK_SECONDS : existing.blockedUntil();
            return new Attempt(existing.count(), existing.windowStart(), blockedUntil);
        });
    }

    public void recordSuccess(String ip) {
        attempts.remove(ip);
    }

    public long getRetryAfterSeconds(String ip) {
        Attempt a = attempts.get(ip);
        if (a == null) return 0;
        long now = Instant.now().getEpochSecond();
        return Math.max(0, a.blockedUntil() - now);
    }
}
