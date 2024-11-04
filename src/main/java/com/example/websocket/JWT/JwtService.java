package com.example.websocket.JWT;

import com.example.websocket.repo.UserRepository;
import com.example.websocket.service.AuthService;
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

    public JwtService(JwtUtil jwtUtil,@Lazy AuthService authService, UserRepository userRepository) {
        this.jwtUtil = jwtUtil;
        this.authService = authService;
        this.userRepository = userRepository;
    }

    public String extractToken(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        return (bearerToken != null && bearerToken.startsWith("Bearer ")) ? bearerToken.substring(7) : null;
    }

    public boolean validateToken(String token) {
        try {
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
}
