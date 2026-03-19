package com.example.websocket.JWT;

import com.example.websocket.repo.UserRepository;
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
    private final UserRepository userRepository;
    private final TokenBlacklistService blacklistService;

    public JwtService(JwtUtil jwtUtil, @Lazy AuthService authService,
                      UserRepository userRepository, TokenBlacklistService blacklistService) {
        this.jwtUtil = jwtUtil;
        this.authService = authService;
        this.userRepository = userRepository;
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
            if (blacklistService.isBlacklisted(token)) return false;
            String username = jwtUtil.extractUsername(token);
            return username != null && userRepository.existsByUsername(username) && !jwtUtil.isTokenExpired(token);
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
