package com.example.websocket.JWT;

import com.example.websocket.service.AuthService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

@Service
public class JwtService {

    private final JwtUtil jwtUtil;
    private final AuthService authService;
    private final TokenBlacklistService blacklistService;

    public JwtService(JwtUtil jwtUtil, @Lazy AuthService authService,
                      TokenBlacklistService blacklistService) {
        this.jwtUtil = jwtUtil;
        this.authService = authService;
        this.blacklistService = blacklistService;
    }

    /**
     * Extract token ONLY from the HttpOnly cookie.
     * The token is never read from the Authorization header anymore —
     * that way it never appears in any JS-visible location.
     */
    public String extractToken(HttpServletRequest request) {
        if (request.getCookies() != null) {
            for (Cookie cookie : request.getCookies()) {
                if ("AUTH_TOKEN".equals(cookie.getName())) {
                    return cookie.getValue();
                }
            }
        }
        return null;
    }

    public boolean validateToken(String token) {
        try {
            // 1. Blacklist check (in-memory — fast, O(1))
            if (blacklistService.isBlacklisted(token)) return false;
            // 2. Signature + expiry check (CPU only — no I/O)
            String username = jwtUtil.extractUsername(token);
            return username != null && !username.isBlank() && !jwtUtil.isTokenExpired(token);
        } catch (Exception e) {
            return false;
        }
    }

    public Authentication getAuthenticationFromToken(String token) {
        String username = jwtUtil.extractUsername(token);
        UserDetails userDetails = authService.loadUserByUsername(username);
        return new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
    }

    /**
     * Blacklist a token (used on logout).
     */
    public void invalidateToken(String token) {
        try {
            java.util.Date expiry = jwtUtil.extractExpiration(token);
            blacklistService.blacklist(token, expiry);
        } catch (Exception ignored) {}
    }
}
