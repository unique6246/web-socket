package com.example.websocket.controller;

import com.example.websocket.JWT.JwtService;
import com.example.websocket.JWT.JwtUtil;
import com.example.websocket.JWT.WsTicketService;
import com.example.websocket.config.LoginRateLimiter;
import com.example.websocket.config.SocketConnectionHandler;
import com.example.websocket.model.User;
import com.example.websocket.repo.UserRepository;
import com.example.websocket.service.AuthService;
import com.example.websocket.service.UserProfileService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final AuthenticationManager authenticationManager;
    private final JwtUtil jwtUtil;
    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final LoginRateLimiter rateLimiter;
    private final WsTicketService wsTicketService;
    private final UserProfileService userProfileService;
    private final SocketConnectionHandler socketHandler;

    public AuthController(AuthService authService, AuthenticationManager authenticationManager,
                          JwtUtil jwtUtil, JwtService jwtService,
                          UserRepository userRepository, LoginRateLimiter rateLimiter,
                          WsTicketService wsTicketService, UserProfileService userProfileService,
                          @Lazy SocketConnectionHandler socketHandler) {
        this.authService = authService;
        this.authenticationManager = authenticationManager;
        this.jwtUtil = jwtUtil;
        this.jwtService = jwtService;
        this.userRepository = userRepository;
        this.rateLimiter = rateLimiter;
        this.wsTicketService = wsTicketService;
        this.userProfileService = userProfileService;
        this.socketHandler = socketHandler;
    }

    @PostMapping("/login")
    public ResponseEntity<?> createAuthenticationToken(@RequestBody User authRequest,
                                                        HttpServletRequest request,
                                                        HttpServletResponse response) {
        String ip = getClientIp(request);

        // Rate limit check
        if (rateLimiter.isBlocked(ip)) {
            long retryAfter = rateLimiter.getRetryAfterSeconds(ip);
            response.setHeader("Retry-After", String.valueOf(retryAfter));
            return ResponseEntity.status(429).body(
                Map.of("error", "Too many failed login attempts. Try again in " + retryAfter + " seconds."));
        }

        // Input validation
        if (authRequest.getUsername() == null || authRequest.getUsername().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Username is required."));
        }
        if (authRequest.getPassword() == null || authRequest.getPassword().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Password is required."));
        }

        try {
            authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(
                    authRequest.getUsername().trim(), authRequest.getPassword()));
        } catch (AuthenticationException e) {
            rateLimiter.recordFailure(ip);
            return ResponseEntity.status(401).body(Map.of("error", "Invalid username or password."));
        }

        rateLimiter.recordSuccess(ip);

        final UserDetails userDetails = authService.loadUserByUsername(authRequest.getUsername().trim());
        String token = jwtUtil.generateToken(userDetails);

        // Load fresh roles from DB
        User dbUser = userRepository.findByUsername(authRequest.getUsername().trim());
        List<String> roles = dbUser.getRoles().stream()
                .map(r -> "ROLE_" + r.getName())
                .collect(Collectors.toList());

        // Set user ONLINE on login (respects manual override) and broadcast actual status
        try {
            userProfileService.setOnline(dbUser.getUsername());  // no-op if manualStatusOverride=true
            // Re-read to get the actual status (may still be AWAY/DND if manually set)
            User freshUser = userRepository.findByUsername(dbUser.getUsername());
            String actualStatus = (freshUser != null && freshUser.getStatus() != null)
                    ? freshUser.getStatus().name() : "ONLINE";
            socketHandler.broadcastPresenceGlobally(dbUser.getUsername(), actualStatus);
        } catch (Exception ignored) {}

        // ── HttpOnly cookie ───────────────────────────────────────────────
        Cookie authCookie = new Cookie("AUTH_TOKEN", token);
        authCookie.setHttpOnly(true);
        authCookie.setSecure(false);   // set true behind HTTPS in production
        authCookie.setPath("/");
        authCookie.setMaxAge(2 * 60 * 60); // 2 hours
        // SameSite=Lax ensures the cookie is sent on top-level navigations
        // (e.g. clicking a link to /api/v1/dashboard) as well as AJAX calls.
        response.addCookie(authCookie);
        response.setHeader("Set-Cookie",
            "AUTH_TOKEN=" + token
            + "; Path=/"
            + "; HttpOnly"
            + "; SameSite=Lax"
            + "; Max-Age=" + (2 * 60 * 60));

        // Return username, roles, and profile fields — NO token in body
        Map<String, Object> resp = new HashMap<>();
        resp.put("username",    userDetails.getUsername());
        resp.put("roles",       roles);
        resp.put("displayName", dbUser.getDisplayName() != null ? dbUser.getDisplayName() : dbUser.getUsername());
        resp.put("avatarUrl",   dbUser.getAvatarUrl()   != null ? dbUser.getAvatarUrl()   : "");
        resp.put("email",       dbUser.getEmail()       != null ? dbUser.getEmail()        : "");
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody User registrationRequest) {
        // Validate username
        if (registrationRequest.getUsername() == null || registrationRequest.getUsername().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Username is required."));
        }
        if (registrationRequest.getUsername().length() < 3 || registrationRequest.getUsername().length() > 50) {
            return ResponseEntity.badRequest().body(Map.of("error", "Username must be 3–50 characters."));
        }
        if (!registrationRequest.getUsername().matches("^[a-zA-Z0-9_.-]+$")) {
            return ResponseEntity.badRequest().body(Map.of("error", "Username may only contain letters, numbers, _, ., -"));
        }
        // Validate password strength
        String pwd = registrationRequest.getPassword();
        String pwdError = validatePasswordStrength(pwd);
        if (pwdError != null) {
            return ResponseEntity.badRequest().body(Map.of("error", pwdError));
        }
        // Validate email
        if (registrationRequest.getEmail() == null || registrationRequest.getEmail().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Email is required."));
        }
        if (!registrationRequest.getEmail().matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid email format."));
        }
        return authService.registerUser(registrationRequest);
    }

    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<?> getCurrentUser(HttpServletRequest request) {
        String token = jwtService.extractToken(request);
        if (token == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        }
        try {
            String username = jwtUtil.extractUsername(token);
            if (username == null || jwtUtil.isTokenExpired(token)) {
                return ResponseEntity.status(401).body(Map.of("error", "Token expired or invalid"));
            }
            User dbUser = userRepository.findByUsername(username);
            if (dbUser == null) {
                return ResponseEntity.status(401).body(Map.of("error", "User not found"));
            }
            List<String> roles = dbUser.getRoles().stream()
                    .map(r -> "ROLE_" + r.getName())
                    .collect(Collectors.toList());
            Map<String, Object> resp = new HashMap<>();
            resp.put("username", username);
            resp.put("roles", roles);
            resp.put("displayName", dbUser.getDisplayName() != null ? dbUser.getDisplayName() : username);
            resp.put("avatarUrl", dbUser.getAvatarUrl() != null ? dbUser.getAvatarUrl() : "");
            resp.put("bio", dbUser.getBio() != null ? dbUser.getBio() : "");
            resp.put("status", dbUser.getStatus() != null ? dbUser.getStatus().name() : "ONLINE");
            resp.put("emailVerified", dbUser.isEmailVerified());
            resp.put("email", dbUser.getEmail() != null ? dbUser.getEmail() : "");
            resp.put("phone", dbUser.getPhone() != null ? dbUser.getPhone() : "");
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            return ResponseEntity.status(401).body(Map.of("error", "Invalid token"));
        }
    }

    @GetMapping("/verify-email")
    public void verifyEmail(@RequestParam String token,
                            HttpServletResponse response) throws java.io.IOException {
        ResponseEntity<?> result = authService.verifyEmail(token);
        if (result.getStatusCode().is2xxSuccessful()) {
            response.sendRedirect("/api/v1/login?verified=true");
        } else {
            // Extract error message for the redirect
            String msg = "verification_failed";
            if (result.getBody() instanceof java.util.Map<?,?> map && map.containsKey("error")) {
                msg = java.net.URLEncoder.encode(map.get("error").toString(), "UTF-8");
            }
            response.sendRedirect("/api/v1/login?error=" + msg);
        }
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<?> forgotPassword(@RequestBody Map<String, String> body) {
        String email = body.get("email");
        if (email == null || email.isBlank()) return ResponseEntity.badRequest().body(Map.of("error", "Email is required."));
        return authService.requestPasswordReset(email.trim().toLowerCase());
    }

    @PostMapping("/reset-password")
    public ResponseEntity<?> resetPassword(@RequestBody Map<String, String> body) {
        String token    = body.get("token");
        String password = body.get("password");
        if (token == null || token.isBlank()) return ResponseEntity.badRequest().body(Map.of("error", "Token is required."));
        if (password == null || password.isBlank()) return ResponseEntity.badRequest().body(Map.of("error", "Password is required."));
        return authService.resetPassword(token, password);
    }

    @PutMapping("/update-profile")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<?> updateProfile(@RequestBody Map<String, String> body, HttpServletRequest request) {
        String token = jwtService.extractToken(request);
        if (token == null) return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        String username = jwtUtil.extractUsername(token);
        try {
            com.example.websocket.model.UserStatus status = null;
            if (body.get("status") != null) {
                try { status = com.example.websocket.model.UserStatus.valueOf(body.get("status").toUpperCase()); } catch (Exception ignored) {}
            }
            var profile = userProfileService.updateProfile(username, body.get("displayName"), body.get("bio"), body.get("avatarUrl"), body.get("phone"), status);
            return ResponseEntity.ok(profile);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/logout")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<?> logout(HttpServletRequest request, HttpServletResponse response) {
        String token = jwtService.extractToken(request);
        String username = null;
        if (token != null) {
            try { username = jwtUtil.extractUsername(token); } catch (Exception ignored) {}
            jwtService.invalidateToken(token); // blacklist the token
        }

        // On explicit logout, force OFFLINE and clear manual override
        if (username != null) {
            try {
                User u = userRepository.findByUsername(username);
                if (u != null) {
                    u.setManualStatusOverride(false);
                    u.setStatus(com.example.websocket.model.UserStatus.OFFLINE);
                    u.setLastSeen(java.time.LocalDateTime.now());
                    userRepository.save(u);
                }
                socketHandler.broadcastPresenceGlobally(username, "OFFLINE");
            } catch (Exception ignored) {}
        }

        // Clear the auth cookie
        Cookie authCookie = new Cookie("AUTH_TOKEN", "");
        authCookie.setHttpOnly(true);
        authCookie.setSecure(false); // true in production with HTTPS
        authCookie.setPath("/");
        authCookie.setMaxAge(0);
        response.addCookie(authCookie);
        return ResponseEntity.ok(Map.of("message", "Logged out successfully."));
    }

    /**
     * Issues a one-time, short-lived (30s) ticket for WebSocket handshake.
     * The ticket is an opaque random string — NOT the JWT.
     * The frontend passes this as ?ticket=... in the WS URL instead of the real token,
     * so the JWT never appears in the URL, network tab, or browser history.
     */
    @PostMapping("/ws-ticket")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<?> issueWsTicket(HttpServletRequest request) {
        String token = jwtService.extractToken(request);
        if (token == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        }
        String username = jwtUtil.extractUsername(token);
        String ticket = wsTicketService.issueTicket(username);
        return ResponseEntity.ok(Map.of("ticket", ticket));
    }

    @PostMapping("/change-password")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<?> changePassword(@RequestBody Map<String, String> requestBody,
                                             HttpServletRequest request) {
        String token = jwtService.extractToken(request);
        if (token == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        }
        String username = jwtUtil.extractUsername(token);
        String oldPassword = requestBody.get("oldPassword");
        String newPassword = requestBody.get("newPassword");

        if (oldPassword == null || oldPassword.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Current password is required."));
        }
        String pwdError = validatePasswordStrength(newPassword);
        if (pwdError != null) {
            return ResponseEntity.badRequest().body(Map.of("error", pwdError));
        }
        if (oldPassword.equals(newPassword)) {
            return ResponseEntity.badRequest().body(Map.of("error", "New password must differ from current password."));
        }
        return authService.changePassword(username, oldPassword, newPassword);
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private String validatePasswordStrength(String password) {
        return authService.validatePasswordStrength(password);
    }

    private String getClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
