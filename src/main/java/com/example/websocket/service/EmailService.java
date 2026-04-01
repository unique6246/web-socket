package com.example.websocket.service;

import com.example.websocket.model.EmailLog;
import com.example.websocket.model.User;
import com.example.websocket.repo.EmailLogRepository;
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
    private final EmailLogRepository emailLogRepository;

    @Value("${spring.mail.username:noreply@chatapp.com}")
    private String fromAddress;

    @Value("${app.base-url:http://localhost:8080}")
    private String baseUrl;

    @Value("${app.email.enabled:false}")
    private boolean emailEnabled;

    public EmailService(JavaMailSender mailSender, EmailLogRepository emailLogRepository) {
        this.mailSender = mailSender;
        this.emailLogRepository = emailLogRepository;
    }

    @Async
    public void sendVerificationEmail(User user, String token) {
        if (!emailEnabled) {
            log.info("[Email] Skipped verification email for {} (email disabled)", user.getUsername());
            return;
        }
        String link = baseUrl + "/api/auth/verify-email?token=" + token;
        String subject = "Verify your ChatApp email";
        String body = "<div style='font-family:Arial,sans-serif;max-width:600px;margin:auto;padding:20px;border:1px solid #e2e8f0;border-radius:8px;'>"
                + "<div style='text-align:center;margin-bottom:24px;'><span style='font-size:40px;'>💬</span><h1 style='color:#4f46e5;margin:8px 0;'>ChatApp</h1></div>"
                + "<h2 style='color:#1e293b;'>Welcome, " + escHtml(user.getDisplayName()) + "! 👋</h2>"
                + "<p style='color:#475569;'>Thank you for registering. Please verify your email address to activate your account.</p>"
                + "<div style='text-align:center;margin:32px 0;'>"
                + "<a href='" + link + "' style='background:#4f46e5;color:#fff;padding:14px 28px;border-radius:8px;text-decoration:none;font-size:16px;font-weight:600;display:inline-block;'>✅ Verify Email Address</a>"
                + "</div>"
                + "<p style='color:#94a3b8;font-size:14px;'>This link expires in <strong>24 hours</strong>. If you didn't create an account, you can safely ignore this email.</p>"
                + "<hr style='border:none;border-top:1px solid #e2e8f0;margin:24px 0;'>"
                + "<p style='color:#94a3b8;font-size:12px;text-align:center;'>© ChatApp — All rights reserved</p>"
                + "</div>";
        sendHtml(user.getEmail(), subject, body, "VERIFICATION");
    }

    @Async
    public void sendPasswordResetEmail(User user, String token) {
        if (!emailEnabled) {
            log.info("[Email] Skipped password reset email for {} (email disabled)", user.getUsername());
            return;
        }
        String link = baseUrl + "/api/v1/reset-password?token=" + token;
        String subject = "Reset your ChatApp password";
        String body = "<div style='font-family:Arial,sans-serif;max-width:600px;margin:auto;padding:20px;border:1px solid #e2e8f0;border-radius:8px;'>"
                + "<div style='text-align:center;margin-bottom:24px;'><span style='font-size:40px;'>💬</span><h1 style='color:#4f46e5;margin:8px 0;'>ChatApp</h1></div>"
                + "<h2 style='color:#1e293b;'>Password Reset Request</h2>"
                + "<p style='color:#475569;'>Hi " + escHtml(user.getDisplayName()) + ", we received a request to reset your ChatApp password.</p>"
                + "<div style='text-align:center;margin:32px 0;'>"
                + "<a href='" + link + "' style='background:#ef4444;color:#fff;padding:14px 28px;border-radius:8px;text-decoration:none;font-size:16px;font-weight:600;display:inline-block;'>🔑 Reset Password</a>"
                + "</div>"
                + "<p style='color:#94a3b8;font-size:14px;'>This link expires in <strong>1 hour</strong>. If you didn't request a password reset, you can safely ignore this email.</p>"
                + "<hr style='border:none;border-top:1px solid #e2e8f0;margin:24px 0;'>"
                + "<p style='color:#94a3b8;font-size:12px;text-align:center;'>© ChatApp — All rights reserved</p>"
                + "</div>";
        sendHtml(user.getEmail(), subject, body, "PASSWORD_RESET");
    }

    @Async
    public void sendWelcomeEmail(User user) {
        if (!emailEnabled) return;
        String subject = "Welcome to ChatApp! 🎉";
        String body = "<div style='font-family:Arial,sans-serif;max-width:600px;margin:auto;padding:20px;border:1px solid #e2e8f0;border-radius:8px;'>"
                + "<div style='text-align:center;margin-bottom:24px;'><span style='font-size:40px;'>💬</span><h1 style='color:#4f46e5;margin:8px 0;'>ChatApp</h1></div>"
                + "<h2 style='color:#1e293b;'>🎉 Your account is ready, " + escHtml(user.getDisplayName()) + "!</h2>"
                + "<p style='color:#475569;'>Your email has been verified and your ChatApp account is now fully activated. Start chatting, create groups, and connect with people.</p>"
                + "<div style='text-align:center;margin:32px 0;'>"
                + "<a href='" + baseUrl + "/api/v1/chat' style='background:#4f46e5;color:#fff;padding:14px 28px;border-radius:8px;text-decoration:none;font-size:16px;font-weight:600;display:inline-block;'>🚀 Open Chat</a>"
                + "</div>"
                + "<hr style='border:none;border-top:1px solid #e2e8f0;margin:24px 0;'>"
                + "<p style='color:#94a3b8;font-size:12px;text-align:center;'>© ChatApp — All rights reserved</p>"
                + "</div>";
        sendHtml(user.getEmail(), subject, body, "WELCOME");
    }

    @Async
    public void sendOAuthWelcomeEmail(User user, String provider) {
        if (!emailEnabled) return;
        String subject = "Welcome to ChatApp via " + capitalize(provider) + "! 🎉";
        String body = "<div style='font-family:Arial,sans-serif;max-width:600px;margin:auto;padding:20px;border:1px solid #e2e8f0;border-radius:8px;'>"
                + "<div style='text-align:center;margin-bottom:24px;'><span style='font-size:40px;'>💬</span><h1 style='color:#4f46e5;margin:8px 0;'>ChatApp</h1></div>"
                + "<h2 style='color:#1e293b;'>Welcome, " + escHtml(user.getDisplayName()) + "! 🎉</h2>"
                + "<p style='color:#475569;'>You've successfully signed in to ChatApp using your <strong>" + capitalize(provider) + "</strong> account. Your account is ready to use!</p>"
                + "<div style='text-align:center;margin:32px 0;'>"
                + "<a href='" + baseUrl + "/api/v1/chat' style='background:#4f46e5;color:#fff;padding:14px 28px;border-radius:8px;text-decoration:none;font-size:16px;font-weight:600;display:inline-block;'>🚀 Open Chat</a>"
                + "</div>"
                + "<p style='color:#94a3b8;font-size:14px;'>You can always sign in using your " + capitalize(provider) + " account at <a href='" + baseUrl + "/api/v1/login' style='color:#4f46e5;'>" + baseUrl + "/api/v1/login</a></p>"
                + "<hr style='border:none;border-top:1px solid #e2e8f0;margin:24px 0;'>"
                + "<p style='color:#94a3b8;font-size:12px;text-align:center;'>© ChatApp — All rights reserved</p>"
                + "</div>";
        sendHtml(user.getEmail(), subject, body, "OAUTH_WELCOME");
    }

    private void sendHtml(String to, String subject, String htmlBody, String emailType) {
        boolean success = false;
        String errorMsg = null;
        try {
            MimeMessage msg = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(msg, true, "UTF-8");
            helper.setFrom(fromAddress);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(htmlBody, true);
            mailSender.send(msg);
            success = true;
            log.info("[Email] Sent '{}' to {}", subject, to);
        } catch (MessagingException e) {
            errorMsg = e.getMessage();
            log.error("[Email] Failed to send '{}' to {}: {}", subject, to, e.getMessage());
        }

        // Persist email log
        try {
            EmailLog emailLog = new EmailLog();
            emailLog.setRecipient(to);
            emailLog.setSubject(subject);
            emailLog.setEmailType(emailType);
            emailLog.setSuccess(success);
            emailLog.setErrorMessage(errorMsg);
            emailLogRepository.save(emailLog);
        } catch (Exception ex) {
            log.warn("[Email] Could not save email log: {}", ex.getMessage());
        }
    }

    private String escHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase();
    }
}
