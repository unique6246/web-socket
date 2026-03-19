package com.example.websocket.config;

import com.example.websocket.JWT.JwtService;
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

import java.util.List;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtService jwtService;

    public SecurityConfig(JwtService jwtService) {
        this.jwtService = jwtService;
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
                    // Versioned page routes — public
                    "/api/v1/", "/api/v1",
                    "/api/v1/login", "/api/v1/register",
                    // Raw .html kept for internal forwards only
                    "/index.html", "/login.html", "/register.html",
                    "/*.css", "/*.js"
                ).permitAll()
                // WebSocket — auth enforced in HandshakeInterceptor
                .requestMatchers("/ws/**").permitAll()
                // Protected page routes — must be authenticated
                .requestMatchers("/api/v1/chat", "/api/v1/dashboard",
                                  "/chat.html",  "/dashboard.html").authenticated()
                // File uploads: any authenticated user
                .requestMatchers("/api/files/**").hasAnyRole("USER", "ADMIN", "MODERATOR")
                // Admin REST API only
                .requestMatchers("/admin/**").hasRole("ADMIN")
                // Moderator + Admin REST API
                .requestMatchers("/api/moderator/**").hasAnyRole("ADMIN", "MODERATOR")
                // Authenticated REST endpoints
                .requestMatchers("/api/auth/me", "/api/auth/change-password",
                                  "/api/auth/logout", "/api/auth/ws-ticket").authenticated()
                // Everything else needs auth
                .anyRequest().authenticated()
            )
            .sessionManagement(sess -> sess.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            // Return proper 401/403 JSON instead of redirect
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((request, response, authException) -> {
                    response.setStatus(401);
                    response.setContentType("application/json");
                    response.getWriter().write("{\"error\":\"Authentication required.\"}");
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
        // Restrict to same origin — change to your actual domain in production
        config.setAllowedOriginPatterns(List.of("http://localhost:*", "https://localhost:*"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Requested-With"));
        config.setExposedHeaders(List.of("Authorization"));
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
