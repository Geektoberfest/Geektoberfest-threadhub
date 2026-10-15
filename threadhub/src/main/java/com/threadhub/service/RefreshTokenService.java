package com.threadhub.service;

import com.threadhub.exception.InvalidCredentialsException;
import com.threadhub.model.RefreshToken;
import com.threadhub.model.User;
import com.threadhub.repository.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class RefreshTokenService {

    private static final long REFRESH_TOKEN_VALIDITY_DAYS = 7;
    private final RefreshTokenRepository refreshTokenRepository;

    public RefreshToken createRefreshToken(User user) {
        RefreshToken refreshToken = RefreshToken.builder()
                .user(user)
                .token(UUID.randomUUID().toString())
                .expiresAt(LocalDateTime.now().plusDays(REFRESH_TOKEN_VALIDITY_DAYS))
                .build();

        return refreshTokenRepository.save(refreshToken);
    }

    public RefreshToken verifyAndRotate(String tokenString) {
        RefreshToken existingToken = refreshTokenRepository.findByToken(tokenString)
                .orElseThrow(() -> new InvalidCredentialsException("Invalid refresh token"));

        if (existingToken.isRevoked() || existingToken.isExpired()) {
            throw new InvalidCredentialsException("Refresh token is expired or revoked");
        }

        // Single-use rotation: Revoke old token
        existingToken.setRevokedAt(LocalDateTime.now());
        refreshTokenRepository.save(existingToken);

        // Issue new refresh token
        return createRefreshToken(existingToken.getUser());
    }

    public void revokeToken(String tokenString) {
        refreshTokenRepository.findByToken(tokenString).ifPresent(token -> {
            token.setRevokedAt(LocalDateTime.now());
            refreshTokenRepository.save(token);
        });
    }
}