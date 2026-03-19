package com.example.websocket.config;

import com.example.websocket.JWT.JwtService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.Nullable;
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
    protected void doFilterInternal(@Nullable HttpServletRequest request,
                                    @Nullable HttpServletResponse response,
                                    @Nullable FilterChain chain)
            throws ServletException, IOException {
        assert request != null;
        assert response != null;
        assert chain != null;

        // Token is extracted exclusively from the HttpOnly cookie
        String token = jwtService.extractToken(request);

        if (token != null) {
            if (!jwtService.validateToken(token)) {
                // Token present but invalid/expired — reject with 401
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json");
                response.getWriter().write("{\"error\":\"Session expired. Please log in again.\"}");
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
}
