package com.example.websocket.JWT;

import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Issues short-lived one-time tickets for WebSocket handshake.
 * The ticket is a random opaque token (not the JWT) that expires in 30 seconds.
 * This means the actual JWT never appears in the WebSocket URL or query string.
 */
@Service
public class WsTicketService {

    private static final long TICKET_TTL_SECONDS = 30;
    private final SecureRandom random = new SecureRandom();

    private record TicketData(String username, Instant expiresAt) {}

    private final Map<String, TicketData> tickets = new ConcurrentHashMap<>();

    /** Issue a one-time ticket for the given username. Returns the opaque ticket string. */
    public String issueTicket(String username) {
        cleanupExpired();
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        tickets.put(ticket, new TicketData(username, Instant.now().plusSeconds(TICKET_TTL_SECONDS)));
        return ticket;
    }

    /**
     * Validate and consume a ticket (one-time use).
     * Returns the username if valid, null otherwise.
     */
    public String consumeTicket(String ticket) {
        if (ticket == null) return null;
        TicketData data = tickets.remove(ticket); // remove = one-time use
        if (data == null) return null;
        if (Instant.now().isAfter(data.expiresAt())) return null; // expired
        return data.username();
    }

    private void cleanupExpired() {
        Instant now = Instant.now();
        tickets.entrySet().removeIf(e -> now.isAfter(e.getValue().expiresAt()));
    }
}
