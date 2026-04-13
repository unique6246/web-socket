package com.example.websocket.config;

import com.example.websocket.JWT.JwtService;
import com.example.websocket.service.OAuth2UserService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtService jwtService;
    private final OAuth2UserService oAuth2UserService;
    private final OAuth2SuccessHandler oAuth2SuccessHandler;

    /**
     * Comma-separated list of allowed CORS origins.
     * In development this defaults to localhost. In production, set this to
     * your actual frontend domain(s) in application.properties:
     *   app.cors.allowed-origins=https://chat.yourcompany.com
     */
    @Value("${app.cors.allowed-origins:http://localhost:8080,http://localhost:3000}")
    private String allowedOrigins;

    public SecurityConfig(JwtService jwtService,
                          OAuth2UserService oAuth2UserService,
                          OAuth2SuccessHandler oAuth2SuccessHandler) {
        this.jwtService = jwtService;
        this.oAuth2UserService = oAuth2UserService;
        this.oAuth2SuccessHandler = oAuth2SuccessHandler;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .authorizeHttpRequests(auth -> auth
                // Public: REST auth endpoints, static assets, and public pages
                .requestMatchers(
                    "/api/auth/login",
                    "/api/auth/register",
                    "/api/auth/verify-email",
                    "/api/auth/forgot-password",
                    "/api/auth/reset-password",
                    "/api/auth/resend-verification",
                    // OAuth2 endpoints
                    "/oauth2/**", "/login/oauth2/**",
                    // Versioned page routes — public
                    "/api/v1/", "/api/v1",
                    "/api/v1/login", "/api/v1/register",
                    "/api/v1/forgot-password", "/api/v1/reset-password",
                    // Raw .html kept for internal forwards only
                    "/index.html", "/login.html", "/register.html",
                    "/forgot-password.html", "/reset-password.html",
                    "/*.css", "/*.js"
                ).permitAll()
                // WebSocket — auth enforced in HandshakeInterceptor
                .requestMatchers("/ws/**").permitAll()
                // Protected page routes — must be authenticated
                .requestMatchers("/api/v1/chat", "/api/v1/dashboard",
                                  "/api/v1/profile", "/api/v1/kafka-monitor",
                                  "/chat.html", "/dashboard.html",
                                  "/profile.html", "/kafka-monitor.html").authenticated()
                // File uploads: any authenticated user
                .requestMatchers("/api/files/**").hasAnyRole("USER", "ADMIN", "MODERATOR")
                // Admin REST API only
                .requestMatchers("/admin/**").hasRole("ADMIN")
                .requestMatchers("/api/kafka-monitor/**").hasRole("ADMIN")
                // Moderator + Admin REST API
                .requestMatchers("/api/moderator/**").hasAnyRole("ADMIN", "MODERATOR")
                // Authenticated REST endpoints
                .requestMatchers("/api/auth/me", "/api/auth/change-password",
                                  "/api/auth/logout", "/api/auth/ws-ticket",
                                  "/api/auth/add-password").authenticated()
                // Everything else needs auth
                .anyRequest().authenticated()
            )
            // OAuth2 login — session needed just for the redirect dance
            .sessionManagement(sess -> sess
                .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
            .oauth2Login(oauth2 -> oauth2
                .loginPage("/api/v1/login")
                .userInfoEndpoint(u -> u.userService(oAuth2UserService))
                .successHandler(oAuth2SuccessHandler)
                .failureUrl("/api/v1/login?error=oauth_failed")
            )
            // Return proper 401/403 JSON instead of redirect for API calls
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((request, response, authException) -> {
                    String accept = request.getHeader("Accept");
                    if (accept != null && accept.contains("application/json")) {
                        response.setStatus(401);
                        response.setContentType("application/json");
                        response.getWriter().write("{\"error\":\"Authentication required.\"}");
                    } else {
                        response.sendRedirect("/api/v1/login");
                    }
                })
                .accessDeniedHandler((request, response, accessDeniedException) -> {
                    response.setStatus(403);
                    response.setContentType("application/json");
                    response.getWriter().write("{\"error\":\"Access denied. Insufficient permissions.\"}");
                })
            )
            .addFilterBefore(jwtAuthFilter(), UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        // Use explicit origins — wildcard is not allowed when allowCredentials=true.
        // Set app.cors.allowed-origins in application.properties for production.
        List<String> origins = Arrays.asList(allowedOrigins.split(","));
        config.setAllowedOrigins(origins.stream().map(String::trim).toList());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Content-Type", "X-Requested-With"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public JwtAuthFilter jwtAuthFilter() {
        return new JwtAuthFilter(jwtService);
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12); // strength 12 (more secure than default 10)
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration authConfig) throws Exception {
        return authConfig.getAuthenticationManager();
    }
}
