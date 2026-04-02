package com.example.websocket.service;


import com.example.websocket.model.*;
import com.example.websocket.repo.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;


@Service
public class AuthService implements UserDetailsService {

    private static final int    MAX_ATTEMPTS   = 5;
    private static final long   LOCK_MINUTES   = 15;

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailVerificationTokenRepository emailVerificationTokenRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final EmailService emailService;

    public AuthService(UserRepository userRepository,
                       RoleRepository roleRepository,
                       PasswordEncoder passwordEncoder,
                       EmailVerificationTokenRepository emailVerificationTokenRepository,
                       PasswordResetTokenRepository passwordResetTokenRepository,
                       EmailService emailService) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
        this.emailVerificationTokenRepository = emailVerificationTokenRepository;
        this.passwordResetTokenRepository = passwordResetTokenRepository;
        this.emailService = emailService;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User user = userRepository.findByUsername(username);
        if (user == null) {
            throw new UsernameNotFoundException("User not found: " + username);
        }
        List<GrantedAuthority> authorities = user.getRoles().stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role.getName()))
                .collect(Collectors.toList());
        return new org.springframework.security.core.userdetails.User(
                user.getUsername(), user.getPassword(), authorities);
    }

    @Transactional
    public ResponseEntity<?> registerUser(User registrationRequest) {
        if (userRepository.existsByUsername(registrationRequest.getUsername())) {
            return ResponseEntity.badRequest().body(Map.of("error", "Username is already taken."));
        }
        if (registrationRequest.getEmail() != null && userRepository.existsByEmail(registrationRequest.getEmail())) {
            return ResponseEntity.badRequest().body(Map.of("error", "Email is already registered."));
        }
        registrationRequest.setPassword(passwordEncoder.encode(registrationRequest.getPassword()));
        Role userRole = roleRepository.findByName("USER")
                .orElseThrow(() -> new RuntimeException("USER role not found."));
        registrationRequest.setRoles(Collections.singleton(userRole));
        User saved = userRepository.save(registrationRequest);

        // Send email verification
        if (saved.getEmail() != null && !saved.getEmail().isBlank()) {
            String token = UUID.randomUUID().toString();
            EmailVerificationToken evt = new EmailVerificationToken();
            evt.setUser(saved);
            evt.setToken(token);
            evt.setExpiresAt(LocalDateTime.now().plusHours(24));
            emailVerificationTokenRepository.save(evt);
            emailService.sendVerificationEmail(saved, token);
        }

        return ResponseEntity.ok(Map.of("message", "User registered successfully. Check your email to verify your account."));
    }

    /**
     * Returns the username that owns the given email-verification token,
     * without consuming or validating the token. Returns null if not found.
     * Uses JOIN FETCH so the User is fully loaded within the session.
     */
    @Transactional(readOnly = true)
    public String getUsernameForVerificationToken(String token) {
        return emailVerificationTokenRepository.findByTokenWithUser(token)
                .map(evt -> evt.getUser().getUsername())
                .orElse(null);
    }

    @Transactional
    public ResponseEntity<?> verifyEmail(String token) {
        // JOIN FETCH keeps the User proxy initialised inside this transaction
        var optToken = emailVerificationTokenRepository.findByTokenWithUser(token);
        if (optToken.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error", "Invalid verification token."));
        var evt = optToken.get();
        if (evt.isUsed()) return ResponseEntity.badRequest().body(Map.of("error", "Token already used."));
        if (evt.getExpiresAt().isBefore(LocalDateTime.now())) return ResponseEntity.badRequest().body(Map.of("error", "Token expired. Please register again or request a new verification email."));
        evt.setUsed(true);
        emailVerificationTokenRepository.save(evt);
        User user = evt.getUser();
        user.setEmailVerified(true);
        userRepository.save(user);
        // Send welcome email now that the address is confirmed
        emailService.sendWelcomeEmail(user);
        return ResponseEntity.ok(Map.of("message", "Email verified successfully. You can now log in."));
    }

    @Transactional
    public ResponseEntity<?> requestPasswordReset(String email) {
        var optUser = userRepository.findByEmail(email);
        // Always return success to prevent user enumeration
        if (optUser.isEmpty()) return ResponseEntity.ok(Map.of("message", "If that email is registered, a reset link has been sent."));
        User user = optUser.get();
        // Invalidate existing tokens
        passwordResetTokenRepository.deleteByUserId(user.getId());
        String token = UUID.randomUUID().toString();
        PasswordResetToken prt = new PasswordResetToken();
        prt.setUser(user);
        prt.setToken(token);
        prt.setExpiresAt(LocalDateTime.now().plusHours(1));
        passwordResetTokenRepository.save(prt);
        emailService.sendPasswordResetEmail(user, token);
        return ResponseEntity.ok(Map.of("message", "If that email is registered, a reset link has been sent."));
    }

    @Transactional
    public ResponseEntity<?> resetPassword(String token, String newPassword) {
        var optToken = passwordResetTokenRepository.findByToken(token);
        if (optToken.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error", "Invalid or expired reset token."));
        var prt = optToken.get();
        if (prt.isUsed()) return ResponseEntity.badRequest().body(Map.of("error", "Token already used."));
        if (prt.getExpiresAt().isBefore(LocalDateTime.now())) return ResponseEntity.badRequest().body(Map.of("error", "Token expired. Please request a new reset link."));
        String pwdError = validatePasswordStrength(newPassword);
        if (pwdError != null) return ResponseEntity.badRequest().body(Map.of("error", pwdError));
        prt.setUsed(true);
        passwordResetTokenRepository.save(prt);
        User user = prt.getUser();
        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);
        return ResponseEntity.ok(Map.of("message", "Password reset successfully. You can now log in."));
    }

    public ResponseEntity<?> changePassword(String username, String oldPassword, String newPassword) {
        User user = userRepository.findByUsername(username);
        if (user == null) return ResponseEntity.notFound().build();
        if (!passwordEncoder.matches(oldPassword, user.getPassword())) {
            return ResponseEntity.badRequest().body(Map.of("error", "Current password is incorrect."));
        }
        String pwdError = validatePasswordStrength(newPassword);
        if (pwdError != null) return ResponseEntity.badRequest().body(Map.of("error", pwdError));
        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);
        return ResponseEntity.ok(Map.of("message", "Password changed successfully."));
    }

    /**
     * Sets the initial password for OAuth users who don't have one yet.
     */
    @Transactional
    public ResponseEntity<?> setInitialPassword(String username, String newPassword) {
        User user = userRepository.findByUsername(username);
        if (user == null) return ResponseEntity.notFound().build();
        if (user.isPasswordSet()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Password already set. Use change-password instead."));
        }
        String pwdError = validatePasswordStrength(newPassword);
        if (pwdError != null) return ResponseEntity.badRequest().body(Map.of("error", pwdError));
        user.setPassword(passwordEncoder.encode(newPassword));
        user.setPasswordSet(true);
        userRepository.save(user);
        return ResponseEntity.ok(Map.of("message", "Password set successfully! You can now log in with your username and password."));
    }

    public ResponseEntity<?> assignRole(String username, String roleName) {
        User user = userRepository.findByUsername(username);
        if (user == null) return ResponseEntity.notFound().build();
        Role role = roleRepository.findByName(roleName.toUpperCase())
                .orElseThrow(() -> new RuntimeException("Role not found: " + roleName));
        user.getRoles().add(role);
        userRepository.save(user);
        return ResponseEntity.ok("Role " + roleName + " assigned to user " + username);
    }

    public ResponseEntity<?> removeRole(String username, String roleName) {
        User user = userRepository.findByUsername(username);
        if (user == null) return ResponseEntity.notFound().build();
        user.getRoles().removeIf(r -> r.getName().equalsIgnoreCase(roleName));
        userRepository.save(user);
        return ResponseEntity.ok("Role " + roleName + " removed from user " + username);
    }

    public String validatePasswordStrength(String password) {
        if (password == null || password.length() < 8) return "Password must be at least 8 characters.";
        if (password.length() > 128) return "Password must not exceed 128 characters.";
        if (!password.matches(".*[A-Z].*")) return "Password must contain at least one uppercase letter.";
        if (!password.matches(".*[a-z].*")) return "Password must contain at least one lowercase letter.";
        if (!password.matches(".*\\d.*")) return "Password must contain at least one digit.";
        if (!password.matches(".*[!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>/?`~].*"))
            return "Password must contain at least one special character.";
        return null;
    }

    /**
     * Record a failed login for this user account.
     * Locks the account after MAX_ATTEMPTS and sends a lock email.
     */
    @Transactional
    public void recordUserFailure(String username) {
        User user = userRepository.findByUsername(username);
        if (user == null) return;
        int attempts = user.getFailedLoginAttempts() + 1;
        user.setFailedLoginAttempts(attempts);
        if (attempts >= MAX_ATTEMPTS) {
            user.setLockedUntil(LocalDateTime.now().plusMinutes(LOCK_MINUTES));
            userRepository.save(user);
            emailService.sendAccountLockedEmail(user);
        } else {
            userRepository.save(user);
        }
    }

    /** Clear failed attempts on successful login */
    @Transactional
    public void recordUserSuccess(String username) {
        User user = userRepository.findByUsername(username);
        if (user == null) return;
        if (user.getFailedLoginAttempts() > 0 || user.getLockedUntil() != null) {
            user.setFailedLoginAttempts(0);
            user.setLockedUntil(null);
            userRepository.save(user);
        }
    }

    /** Returns true if this user account is currently locked */
    public boolean isUserLocked(String username) {
        User user = userRepository.findByUsername(username);
        if (user == null) return false;
        if (user.getLockedUntil() == null) return false;
        if (LocalDateTime.now().isBefore(user.getLockedUntil())) return true;
        // Lock expired — clear it
        user.setLockedUntil(null);
        user.setFailedLoginAttempts(0);
        userRepository.save(user);
        return false;
    }

    /** Seconds remaining until the account lock expires */
    public long getUserLockedSeconds(String username) {
        User user = userRepository.findByUsername(username);
        if (user == null || user.getLockedUntil() == null) return 0;
        long secs = java.time.Duration.between(LocalDateTime.now(), user.getLockedUntil()).getSeconds();
        return Math.max(0, secs);
    }
}
