package com.example.websocket.service;

import com.example.websocket.model.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    private final JavaMailSender mailSender;

    @Value("${spring.mail.username:noreply@chatapp.com}")
    private String fromAddress;

    @Value("${app.base-url:http://localhost:8080}")
    private String baseUrl;

    @Value("${app.email.enabled:false}")
    private boolean emailEnabled;

    public EmailService(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    @Async
    public void sendVerificationEmail(User user, String token) {
        if (!emailEnabled) { log.info("[Email] Skipped verification email for {} (email disabled)", user.getUsername()); return; }
        String link = baseUrl + "/api/auth/verify-email?token=" + token;
        String subject = "Verify your ChatApp email";
        String body = "<h2>Welcome to ChatApp, " + escHtml(user.getDisplayName()) + "!</h2>"
                + "<p>Please verify your email address by clicking the link below:</p>"
                + "<p><a href='" + link + "' style='background:#4f46e5;color:#fff;padding:10px 20px;border-radius:6px;text-decoration:none;'>Verify Email</a></p>"
                + "<p>This link expires in 24 hours.</p>"
                + "<p>If you did not sign up, you can ignore this email.</p>";
        sendHtml(user.getEmail(), subject, body);
    }

    @Async
    public void sendPasswordResetEmail(User user, String token) {
        if (!emailEnabled) { log.info("[Email] Skipped password reset email for {} (email disabled)", user.getUsername()); return; }
        String link = baseUrl + "/api/v1/reset-password?token=" + token;
        String subject = "Reset your ChatApp password";
        String body = "<h2>Password Reset Request</h2>"
                + "<p>Hi " + escHtml(user.getDisplayName()) + ",</p>"
                + "<p>We received a request to reset your password. Click the button below to set a new password:</p>"
                + "<p><a href='" + link + "' style='background:#4f46e5;color:#fff;padding:10px 20px;border-radius:6px;text-decoration:none;'>Reset Password</a></p>"
                + "<p>This link expires in 1 hour.</p>"
                + "<p>If you didn't request a password reset, you can safely ignore this email.</p>";
        sendHtml(user.getEmail(), subject, body);
    }

    @Async
    public void sendWelcomeEmail(User user) {
        if (!emailEnabled) return;
        String subject = "Welcome to ChatApp! 🎉";
        String body = "<h2>Hey " + escHtml(user.getDisplayName()) + "!</h2>"
                + "<p>Your ChatApp account is ready. Start chatting, create groups, and connect with people.</p>"
                + "<p><a href='" + baseUrl + "/api/v1/chat' style='background:#4f46e5;color:#fff;padding:10px 20px;border-radius:6px;text-decoration:none;'>Open Chat</a></p>";
        sendHtml(user.getEmail(), subject, body);
    }

    private void sendHtml(String to, String subject, String htmlBody) {
        try {
            MimeMessage msg = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(msg, true, "UTF-8");
            helper.setFrom(fromAddress);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(htmlBody, true);
            mailSender.send(msg);
            log.info("[Email] Sent '{}' to {}", subject, to);
        } catch (MessagingException e) {
            log.error("[Email] Failed to send '{}' to {}: {}", subject, to, e.getMessage());
        }
    }

    private String escHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
