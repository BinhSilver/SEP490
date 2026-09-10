package com.jlas.auth.repository;

import com.jlas.auth.entity.AuthToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Repository thao tác dữ liệu cho {@link AuthToken}.
 */
@Repository
public interface AuthTokenRepository extends JpaRepository<AuthToken, Long> {

    Optional<AuthToken> findByTokenHash(String tokenHash);

    Optional<AuthToken> findByTokenTypeAndTokenHash(String tokenType, String tokenHash);

    Optional<AuthToken> findByTokenTypeAndOtpHashAndUser_EmailAndUsedAtIsNullAndRevokedAtIsNull(
            String tokenType, String otpHash, String email);

    Optional<AuthToken> findFirstByUser_EmailAndTokenTypeOrderByCreatedAtDesc(String email, String tokenType);

    List<AuthToken> findByUser_IdAndTokenTypeAndUsedAtIsNullAndRevokedAtIsNull(Long userId, String tokenType);

    long countByUser_IdAndTokenTypeAndCreatedAtAfter(Long userId, String tokenType, LocalDateTime createdAfter);

    void deleteByUser_IdAndTokenType(Long userId, String tokenType);
}
