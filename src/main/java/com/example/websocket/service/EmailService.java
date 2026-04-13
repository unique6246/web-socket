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

    @Value("${spring.mail.username:pintu}")
    private String smtpUsername;

    @Value("${app.email.from:pintu@chatapp.com}")
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
    public void sendLoginNotification(User user, String ip, String deviceHint) {
        if (!emailEnabled) return;
        String subject = "New sign-in to your ChatApp account";
        String time = java.time.format.DateTimeFormatter
                .ofPattern("dd MMM yyyy, HH:mm 'UTC'")
                .format(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC));
        String body = "<div style='font-family:Arial,sans-serif;max-width:600px;margin:auto;padding:20px;border:1px solid #e2e8f0;border-radius:8px;'>"
                + "<div style='text-align:center;margin-bottom:24px;'><span style='font-size:40px;'>💬</span><h1 style='color:#4f46e5;margin:8px 0;'>ChatApp</h1></div>"
                + "<h2 style='color:#1e293b;'>New sign-in detected 🔔</h2>"
                + "<p style='color:#475569;'>Hi <strong>" + escHtml(user.getDisplayName()) + "</strong>, we noticed a new sign-in to your account.</p>"
                + "<table style='width:100%;border-collapse:collapse;margin:16px 0;'>"
                + "<tr style='background:#f8fafc;'><td style='padding:10px;border:1px solid #e2e8f0;color:#64748b;width:40%;'>Time</td><td style='padding:10px;border:1px solid #e2e8f0;color:#1e293b;'>" + time + "</td></tr>"
                + "<tr><td style='padding:10px;border:1px solid #e2e8f0;color:#64748b;'>IP Address</td><td style='padding:10px;border:1px solid #e2e8f0;color:#1e293b;'>" + escHtml(ip) + "</td></tr>"
                + "<tr style='background:#f8fafc;'><td style='padding:10px;border:1px solid #e2e8f0;color:#64748b;'>Device</td><td style='padding:10px;border:1px solid #e2e8f0;color:#1e293b;'>" + escHtml(deviceHint) + "</td></tr>"
                + "</table>"
                + "<p style='color:#475569;'>If this was you, no action is needed.</p>"
                + "<p style='color:#ef4444;font-weight:600;'>If this wasn't you, reset your password immediately:</p>"
                + "<div style='text-align:center;margin:20px 0;'>"
                + "<a href='" + baseUrl + "/api/v1/forgot-password' style='background:#ef4444;color:#fff;padding:12px 24px;border-radius:8px;text-decoration:none;font-size:15px;font-weight:600;display:inline-block;'>🔑 Secure My Account</a>"
                + "</div>"
                + "<hr style='border:none;border-top:1px solid #e2e8f0;margin:24px 0;'>"
                + "<p style='color:#94a3b8;font-size:12px;text-align:center;'>© ChatApp — All rights reserved</p>"
                + "</div>";
        sendHtml(user.getEmail(), subject, body, "LOGIN_NOTIFICATION");
    }

    @Async
    public void sendAccountLockedEmail(User user) {
        if (!emailEnabled) return;
        String subject = "Your ChatApp account has been temporarily locked";
        String body = "<div style='font-family:Arial,sans-serif;max-width:600px;margin:auto;padding:20px;border:1px solid #e2e8f0;border-radius:8px;'>"
                + "<div style='text-align:center;margin-bottom:24px;'><span style='font-size:40px;'>💬</span><h1 style='color:#4f46e5;margin:8px 0;'>ChatApp</h1></div>"
                + "<h2 style='color:#ef4444;'>Account Temporarily Locked 🔒</h2>"
                + "<p style='color:#475569;'>Hi <strong>" + escHtml(user.getDisplayName()) + "</strong>, your account has been locked for <strong>15 minutes</strong> due to too many failed login attempts.</p>"
                + "<p style='color:#475569;'>You can try again after the lockout period, or reset your password now:</p>"
                + "<div style='text-align:center;margin:24px 0;'>"
                + "<a href='" + baseUrl + "/api/v1/forgot-password' style='background:#4f46e5;color:#fff;padding:12px 24px;border-radius:8px;text-decoration:none;font-size:15px;font-weight:600;display:inline-block;'>🔑 Reset Password</a>"
                + "</div>"
                + "<p style='color:#94a3b8;font-size:14px;'>If you did not attempt to log in, your password may be compromised. We recommend resetting it.</p>"
                + "<hr style='border:none;border-top:1px solid #e2e8f0;margin:24px 0;'>"
                + "<p style='color:#94a3b8;font-size:12px;text-align:center;'>© ChatApp — All rights reserved</p>"
                + "</div>";
        sendHtml(user.getEmail(), subject, body, "ACCOUNT_LOCKED");
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
        // Use SMTP username as sender when configured (production), otherwise use the from address (MailHog dev)
        String effectiveFrom = (smtpUsername != null && !smtpUsername.isBlank()) ? smtpUsername : fromAddress;
        boolean success = false;
        String errorMsg = null;
        try {
            MimeMessage msg = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(msg, true, "UTF-8");
            helper.setFrom(effectiveFrom);
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
