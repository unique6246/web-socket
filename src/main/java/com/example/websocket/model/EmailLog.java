package com.example.websocket.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "email_logs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class EmailLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String recipient;

    @Column(nullable = false, length = 200)
    private String subject;

    /** Type of email: VERIFICATION, WELCOME, PASSWORD_RESET, etc. */
    @Column(nullable = false, length = 50)
    private String emailType;

    @Column(nullable = false)
    private boolean success;

    private String errorMessage;

    @Column(nullable = false)
    private LocalDateTime sentAt = LocalDateTime.now();
}
