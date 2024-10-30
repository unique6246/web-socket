package com.example.websocket.service;

import com.example.websocket.repo.UserRepository;
import com.example.websocket.utility.JwtUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

@Service
public class JwtService {

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    @Lazy
    private AuthService authService;

    @Autowired
    private UserRepository userRepository;

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
