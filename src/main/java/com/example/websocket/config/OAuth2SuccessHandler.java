package com.example.websocket.config;

import com.example.websocket.JWT.JwtUtil;
import com.example.websocket.model.User;
import com.example.websocket.repo.UserRepository;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
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

    public OAuth2SuccessHandler(JwtUtil jwtUtil, UserRepository userRepository) {
        this.jwtUtil = jwtUtil;
        this.userRepository = userRepository;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request,
                                        HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        OAuth2User oauthUser = (OAuth2User) authentication.getPrincipal();
        Map<String, Object> attrs = oauthUser.getAttributes();

        String email = (String) attrs.get("email");
        if (email == null || email.isBlank()) {
            log.warn("[OAuth2] No email returned from provider — cannot issue JWT");
            response.sendRedirect("/api/v1/login?error=oauth_no_email");
            return;
        }

        User dbUser = userRepository.findByEmail(email)
                .orElse(null);
        if (dbUser == null) {
            log.warn("[OAuth2] No local user found for email {} after OAuth2 success", email);
            response.sendRedirect("/api/v1/login?error=oauth_user_not_found");
            return;
        }

        List<String> roles = dbUser.getRoles().stream()
                .map(r -> "ROLE_" + r.getName())
                .collect(Collectors.toList());

        // Build a UserDetails object to generate the JWT
        List<SimpleGrantedAuthority> authorities = roles.stream()
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toList());
        UserDetails userDetails = new org.springframework.security.core.userdetails.User(
                dbUser.getUsername(), "", authorities);

        String token = jwtUtil.generateToken(userDetails);

        // Set HttpOnly cookie — same as regular login
        response.setHeader("Set-Cookie",
                "AUTH_TOKEN=" + token
                + "; Path=/"
                + "; HttpOnly"
                + "; SameSite=Lax"
                + "; Max-Age=" + (2 * 60 * 60));

        Cookie authCookie = new Cookie("AUTH_TOKEN", token);
        authCookie.setHttpOnly(true);
        authCookie.setSecure(false);
        authCookie.setPath("/");
        authCookie.setMaxAge(2 * 60 * 60);
        response.addCookie(authCookie);

        log.info("[OAuth2] Issued JWT for user {} and redirecting", dbUser.getUsername());

        boolean isAdmin = roles.contains("ROLE_ADMIN");
        response.sendRedirect(isAdmin ? "/api/v1/dashboard" : "/api/v1/chat");
    }
}
