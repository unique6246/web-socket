package com.example.websocket.repo;

import com.example.websocket.model.EmailVerificationToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface EmailVerificationTokenRepository extends JpaRepository<EmailVerificationToken, Long> {

    Optional<EmailVerificationToken> findByToken(String token);

    /** Eagerly loads the associated User in one JOIN — avoids LazyInitializationException */
    @Query("SELECT t FROM EmailVerificationToken t JOIN FETCH t.user WHERE t.token = :token")
    Optional<EmailVerificationToken> findByTokenWithUser(@Param("token") String token);

    @Modifying
    @Query("DELETE FROM EmailVerificationToken t WHERE t.user.id = :userId")
    void deleteByUserId(@Param("userId") Long userId);
}
