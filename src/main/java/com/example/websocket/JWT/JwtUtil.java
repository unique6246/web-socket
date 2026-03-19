package com.example.websocket.JWT;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.SignatureAlgorithm;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

import java.security.Key;
import java.util.*;
import java.util.stream.Collectors;

@Component
public class JwtUtil {

    @Value("${jwt.secret}")
    private String secret;

    private Key getSigningKey() {
        // Convert the secret string to bytes and generate an HMAC key
        return Keys.hmacShaKeyFor(secret.getBytes());
    }

    public String generateToken(UserDetails user) {
        Map<String, Object> claims = new HashMap<>();
        List<String> roles = user.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toList());
        claims.put("roles", roles);
        claims.put("jti", UUID.randomUUID().toString()); // unique token ID for blacklisting
        return createToken(claims, user.getUsername());
    }

    private String createToken(Map<String, Object> claims, String subject) {
        return Jwts.builder()
                .setClaims(claims)
                .setSubject(subject)
                .setIssuedAt(new Date(System.currentTimeMillis()))
                .setExpiration(new Date(System.currentTimeMillis() + 60L * 60 * 1000 * 2)) // 2 hours
                .signWith(getSigningKey(), SignatureAlgorithm.HS256)
                .compact();
    }

    public boolean isTokenExpired(String token) {
        return extractExpiration(token).before(new Date());
    }

    public String extractUsername(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    public Date extractExpiration(String token) {
        return extractClaim(token, Claims::getExpiration);
    }

    @SuppressWarnings("unchecked")
    public List<String> extractRoles(String token) {
        return (List<String>) extractClaim(token, claims -> claims.get("roles"));
    }

    public <T> T extractClaim(String token, java.util.function.Function<Claims, T> claimsResolver) {
        try {
            final Claims claims = Jwts.parserBuilder()
                    .setSigningKey(getSigningKey()) // Use secret directly
                    .build()
                    .parseClaimsJws(token)
                    .getBody();
            return claimsResolver.apply(claims);
        } catch (Exception e) {
            throw new IllegalStateException("Invalid JWT Token", e);
        }
    }
}
