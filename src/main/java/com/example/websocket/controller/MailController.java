package com.example.websocket.controller;

import com.example.websocket.model.EmailVerificationToken;
import com.example.websocket.model.User;
import com.example.websocket.repo.EmailVerificationTokenRepository;
import com.example.websocket.repo.UserRepository;
import com.example.websocket.service.EmailService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Handles email-related REST endpoints:
 *  POST /api/auth/resend-verification  — resend verification email
 */
@RestController
@RequestMapping("/api/auth")
public class MailController {

    private final UserRepository userRepository;
    private final EmailVerificationTokenRepository tokenRepository;
    private final EmailService emailService;

    public MailController(UserRepository userRepository,
                          EmailVerificationTokenRepository tokenRepository,
                          EmailService emailService) {
        this.userRepository = userRepository;
        this.tokenRepository = tokenRepository;
        this.emailService = emailService;
    }

    /**
     * Resend email verification link.
     * Body: { "email": "user@example.com" }
     * Always returns success to prevent user enumeration.
     */
    @PostMapping("/resend-verification")
    public ResponseEntity<?> resendVerification(@RequestBody Map<String, String> body) {
        String email = body.get("email");
        if (email == null || email.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Email is required."));
        }
        email = email.trim().toLowerCase();

        var optUser = userRepository.findByEmail(email);
        if (optUser.isPresent()) {
            User user = optUser.get();
            if (!user.isEmailVerified()) {
                // Invalidate old tokens for this user
                tokenRepository.deleteByUserId(user.getId());

                String token = UUID.randomUUID().toString();
                EmailVerificationToken evt = new EmailVerificationToken();
                evt.setUser(user);
                evt.setToken(token);
                evt.setExpiresAt(LocalDateTime.now().plusHours(24));
                tokenRepository.save(evt);
                emailService.sendVerificationEmail(user, token);
            }
        }

        return ResponseEntity.ok(Map.of(
            "message", "If that email is registered and not yet verified, a new verification link has been sent."));
    }
}
