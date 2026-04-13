package com.example.websocket.config;

import com.example.websocket.JWT.JwtService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Reads the JWT exclusively from the HttpOnly cookie set by the server.
 * The token never travels in an Authorization header, so it is completely
 * invisible in the browser's Network tab, DevTools Application tab,
 * sessionStorage, or localStorage.
 */
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    public JwtAuthFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain)
            throws ServletException, IOException {

        // Token is extracted exclusively from the HttpOnly cookie
        String token = jwtService.extractToken(request);

        if (token != null) {
            if (!jwtService.validateToken(token)) {
                // Token is present but invalid/expired.
                // Only block requests for protected API paths —
                // public paths (login, register, static assets) must continue
                // even when the browser sends a stale/expired cookie.
                String path = request.getServletPath();
                boolean isProtectedApi = path.startsWith("/api/") && !isPublicApiPath(path);
                if (isProtectedApi) {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType("application/json");
                    response.getWriter().write("{\"error\":\"Session expired. Please log in again.\"}");
                    return;
                }
                // Public path with a stale cookie — clear context and proceed normally
                SecurityContextHolder.clearContext();
                chain.doFilter(request, response);
                return;
            }
            var authentication = jwtService.getAuthenticationFromToken(token);
            if (authentication instanceof UsernamePasswordAuthenticationToken authToken) {
                authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            }
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }

        chain.doFilter(request, response);
    }

    /**
     * Returns true for API paths that are publicly accessible (no auth required).
     * Must stay in sync with the permitAll() rules in SecurityConfig.
     */
    private boolean isPublicApiPath(String path) {
        return path.equals("/api/auth/login")
            || path.equals("/api/auth/register")
            || path.equals("/api/auth/verify-email")
            || path.equals("/api/auth/forgot-password")
            || path.equals("/api/auth/reset-password")
            || path.equals("/api/auth/resend-verification");
    }
}
