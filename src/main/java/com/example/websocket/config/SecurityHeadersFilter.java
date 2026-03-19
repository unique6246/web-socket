package com.example.websocket.config;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Adds HTTP security headers to every response to protect against common web vulnerabilities.
 */
@Component
@Order(1)
public class SecurityHeadersFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletResponse httpResponse = (HttpServletResponse) response;

        // Prevent MIME-type sniffing
        httpResponse.setHeader("X-Content-Type-Options", "nosniff");

        // Prevent clickjacking
        httpResponse.setHeader("X-Frame-Options", "DENY");

        // Enable browser XSS protection (legacy browsers)
        httpResponse.setHeader("X-XSS-Protection", "1; mode=block");

        // Force HTTPS (HSTS) – enable when behind HTTPS
        // httpResponse.setHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains");

        // Referrer policy
        httpResponse.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");

        // Permissions policy — disable unused browser features
        httpResponse.setHeader("Permissions-Policy",
                "camera=(), microphone=(), geolocation=(), payment=()");

        // Content-Security-Policy
        httpResponse.setHeader("Content-Security-Policy",
                "default-src 'self'; " +
                "script-src 'self' 'unsafe-inline' https://stackpath.bootstrapcdn.com; " +
                "style-src 'self' 'unsafe-inline' https://stackpath.bootstrapcdn.com; " +
                "connect-src 'self' ws: wss:; " +
                "img-src 'self' data: blob: https://res.cloudinary.com; " +
                "font-src 'self' https://stackpath.bootstrapcdn.com; " +
                "object-src 'none'; " +
                "frame-ancestors 'none';");

        // Cache control — don't cache sensitive pages
        String uri = ((jakarta.servlet.http.HttpServletRequest) request).getRequestURI();
        if (uri.endsWith(".html") || uri.startsWith("/api/")) {
            httpResponse.setHeader("Cache-Control", "no-store, no-cache, must-revalidate");
            httpResponse.setHeader("Pragma", "no-cache");
        }

        chain.doFilter(request, response);
    }
}
