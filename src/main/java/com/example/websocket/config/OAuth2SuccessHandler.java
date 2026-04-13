package com.example.websocket.config;

import com.example.websocket.JWT.JwtUtil;
import com.example.websocket.model.Role;
import com.example.websocket.model.User;
import com.example.websocket.repo.RoleRepository;
import com.example.websocket.repo.UserRepository;
import com.example.websocket.service.EmailService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * After a successful OAuth2 login (Google/GitHub), this handler:
 *  1. Finds the local User by email returned by the provider
 *  2. Issues the same HttpOnly JWT cookie used for regular logins
 *  3. Redirects the browser to /api/v1/chat (or /api/v1/dashboard for admins)
 */
@Component
public class OAuth2SuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private static final Logger log = LoggerFactory.getLogger(OAuth2SuccessHandler.class);

    private final JwtUtil jwtUtil;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final EmailService emailService;

    public OAuth2SuccessHandler(JwtUtil jwtUtil,
                                UserRepository userRepository,
                                RoleRepository roleRepository,
                                EmailService emailService) {
        this.jwtUtil = jwtUtil;
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.emailService = emailService;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request,
                                        HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        OAuth2User oauthUser = (OAuth2User) authentication.getPrincipal();
        Map<String, Object> attrs = oauthUser.getAttributes();

        // ── Extract provider info ─────────────────────────────────────────
        String provider = "oauth";
        if (authentication instanceof OAuth2AuthenticationToken oauthToken) {
            provider = oauthToken.getAuthorizedClientRegistrationId(); // "google" or "github"
        }

        String providerUserId = String.valueOf(
                attrs.get("sub") != null ? attrs.get("sub") : attrs.get("id"));
        String email   = (String) attrs.get("email");
        String name    = (String) attrs.get("name");
        String picture = (String) attrs.get("avatar_url"); // GitHub
        if (picture == null) picture = (String) attrs.get("picture"); // Google

        // ── 1. Try to find existing user ──────────────────────────────────
        User dbUser = null;

        // 1a. Look up by email (most common)
        if (email != null && !email.isBlank()) {
            dbUser = userRepository.findByEmail(email).orElse(null);
        }

        // 1b. Fallback: look up by provider + providerUserId
        if (dbUser == null) {
            dbUser = userRepository.findByProviderAndProviderUserId(provider, providerUserId)
                    .orElse(null);
        }

        // ── 2. Auto-register if user doesn't exist ───────────────────────
        if (dbUser == null) {
            if (email == null || email.isBlank()) {
                log.warn("[OAuth2] No email returned from provider and no existing account — create user");
                response.sendRedirect("/api/v1/login?error=oauth_no_email");
                return;
            }

            // Derive username from email prefix (e.g. praveenwppe@gmail.com → praveenwppe)
            String baseUsername = email.substring(0, email.indexOf('@'))
                                       .replaceAll("[^a-zA-Z0-9_.-]", "_");
            if (baseUsername.length() > 50) baseUsername = baseUsername.substring(0, 50);

            dbUser = new User();
            dbUser.setUsername(generateUniqueUsername(baseUsername));
            dbUser.setPassword("");  // no password — authenticated via OAuth provider
            dbUser.setEmail(email);
            dbUser.setDisplayName(name != null ? name : baseUsername);
            dbUser.setAvatarUrl(picture);
            dbUser.setProvider(provider);
            dbUser.setProviderUserId(providerUserId);
            dbUser.setEmailVerified(true); // OAuth provider already verified the email

            Role userRole = roleRepository.findByName("USER")
                    .orElseThrow(() -> new RuntimeException("USER role not found in DB"));
            dbUser.setRoles(Collections.singleton(userRole));
            dbUser = userRepository.save(dbUser);

            log.info("[OAuth2] Auto-registered new user {} via {}", dbUser.getUsername(), provider);

            // Send welcome email for new OAuth users
            emailService.sendOAuthWelcomeEmail(dbUser, provider);
        } else {
            // Link provider if not already linked (e.g. user registered with password, now logs in via Google)
            if (dbUser.getProvider() == null) {
                dbUser.setProvider(provider);
                dbUser.setProviderUserId(providerUserId);
                dbUser.setEmailVerified(true);
                userRepository.save(dbUser);
                log.info("[OAuth2] Linked {} account to existing user {}", provider, dbUser.getUsername());
            }
        }

        List<String> roles = dbUser.getRoles().stream()
                .map(r -> "ROLE_" + r.getName())
                .toList();

        // Build a UserDetails object to generate the JWT
        List<SimpleGrantedAuthority> authorities = roles.stream()
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toList());
        UserDetails userDetails = new org.springframework.security.core.userdetails.User(
                dbUser.getUsername(), "", authorities);

        String token = jwtUtil.generateToken(userDetails);

        // Set HttpOnly cookie using the structured Set-Cookie header for SameSite support.
        // Do NOT also call response.addCookie() — that would write two Set-Cookie headers.
        int maxAgeSecs = 2 * 60 * 60; // 2 hours
        response.setHeader("Set-Cookie",
                "AUTH_TOKEN=" + token
                + "; Path=/"
                + "; HttpOnly"
                + "; SameSite=Lax"
                + "; Max-Age=" + maxAgeSecs);

        // ── Send login notification email (async — non-blocking) ─────────
        if (dbUser.getEmail() != null && !dbUser.getEmail().isBlank()) {
            String ip = getClientIp(request);
            String ua = request.getHeader("User-Agent");
            String deviceHint = parseDeviceHint(ua);
            emailService.sendLoginNotification(dbUser, ip, deviceHint);
        }

        // NOTE: Online presence is NOT set here.
        // The user becomes ONLINE when their WebSocket connection is established
        // in SocketConnectionHandler.afterConnectionEstablished(), which is the
        // correct and single source of truth for presence state.

        log.info("[OAuth2] Issued JWT for user {} and redirecting", dbUser.getUsername());


        boolean isAdmin = roles.contains("ROLE_ADMIN");
        response.sendRedirect(isAdmin ? "/api/v1/dashboard" : "/api/v1/chat");
    }

    private String getClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private String parseDeviceHint(String ua) {
        if (ua == null || ua.isBlank()) return "Unknown device";
        if (ua.contains("Mobile") || ua.contains("Android") || ua.contains("iPhone")) {
            if (ua.contains("iPhone") || ua.contains("iPad")) return "iPhone / iPad";
            if (ua.contains("Android")) return "Android device";
            return "Mobile device";
        }
        if (ua.contains("Windows")) return "Windows PC";
        if (ua.contains("Macintosh") || ua.contains("Mac OS X")) return "Mac";
        if (ua.contains("Linux")) return "Linux";
        return "Unknown device";
    }

    private String generateUniqueUsername(String base) {
        String candidate = base;
        int suffix = 1;
        while (userRepository.existsByUsername(candidate)) {
            candidate = base + suffix++;
            if (candidate.length() > 50) candidate = base.substring(0, 45) + suffix++;
        }
        return candidate;
    }
}
