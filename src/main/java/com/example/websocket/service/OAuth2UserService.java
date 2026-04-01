package com.example.websocket.service;

import com.example.websocket.model.Role;
import com.example.websocket.model.User;
import com.example.websocket.repo.RoleRepository;
import com.example.websocket.repo.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.Map;

/**
 * Handles OAuth2 user info after a successful OAuth2 authorization.
 * Creates a new local User account if none exists, or links/updates an existing one.
 */
@Service
public class OAuth2UserService extends DefaultOAuth2UserService {

    private static final Logger log = LoggerFactory.getLogger(OAuth2UserService.class);

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final EmailService emailService;

    public OAuth2UserService(UserRepository userRepository,
                              RoleRepository roleRepository,
                              EmailService emailService) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.emailService = emailService;
    }

    @Override
    @Transactional
    public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
        OAuth2User oauthUser = super.loadUser(userRequest);

        String provider = userRequest.getClientRegistration().getRegistrationId(); // "google" or "github"
        Map<String, Object> attrs = oauthUser.getAttributes();

        String providerUserId = String.valueOf(attrs.get("sub") != null ? attrs.get("sub") : attrs.get("id"));
        String email = (String) attrs.get("email");
        String name  = (String) attrs.get("name");
        String picture = (String) attrs.get("avatar_url"); // GitHub
        if (picture == null) picture = (String) attrs.get("picture"); // Google

        // Derive a unique username from provider + id
        String baseUsername = provider + "_" + providerUserId;
        if (baseUsername.length() > 50) baseUsername = baseUsername.substring(0, 50);

        boolean isNewUser = false;
        User user = null;

        // 1. Try to find by provider + providerUserId (most reliable)
        if (email != null && !email.isBlank()) {
            var optByEmail = userRepository.findByEmail(email);
            if (optByEmail.isPresent()) {
                user = optByEmail.get();
                // Link provider if not already linked
                if (user.getProvider() == null) {
                    user.setProvider(provider);
                    user.setProviderUserId(providerUserId);
                    user.setEmailVerified(true);
                    userRepository.save(user);
                    log.info("[OAuth2] Linked {} account to existing user {}", provider, user.getUsername());
                }
            }
        }

        if (user == null) {
            // New OAuth user — create account
            user = new User();
            user.setUsername(generateUniqueUsername(baseUsername));
            user.setPassword(""); // no password for OAuth users
            user.setEmail(email);
            user.setDisplayName(name != null ? name : baseUsername);
            user.setAvatarUrl(picture);
            user.setProvider(provider);
            user.setProviderUserId(providerUserId);
            user.setEmailVerified(true); // email from OAuth provider is already verified

            Role userRole = roleRepository.findByName("USER")
                    .orElseThrow(() -> new RuntimeException("USER role not found"));
            user.setRoles(Collections.singleton(userRole));
            user = userRepository.save(user);
            isNewUser = true;
            log.info("[OAuth2] Created new user {} via {}", user.getUsername(), provider);

            // Send welcome email for new OAuth users
            if (email != null && !email.isBlank()) {
                emailService.sendOAuthWelcomeEmail(user, provider);
            }
        }

        return oauthUser;
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
