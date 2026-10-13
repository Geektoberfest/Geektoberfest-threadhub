package com.threadhub.service;

import com.threadhub.model.RevokedAccessToken;
import com.threadhub.repository.RevokedAccessTokenRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Records revoked access tokens and answers whether a token is revoked.
 *
 * <p>Revocations are stored in the database rather than in a process-local collection so that every
 * application instance reaches the same decision — a token revoked on one instance must not still
 * be accepted on another. Entries are keyed by the JWT {@code jti} claim and are only needed until
 * the token's own expiry, after which normal expiry validation rejects it.</p>
 */
@Service
public class AccessTokenBlacklistService {

    private final RevokedAccessTokenRepository revokedAccessTokenRepository;

    public AccessTokenBlacklistService(RevokedAccessTokenRepository revokedAccessTokenRepository) {
        this.revokedAccessTokenRepository = revokedAccessTokenRepository;
    }

    /** Called on every authenticated request from the JWT validator. */
    @Transactional(readOnly = true)
    public boolean isRevoked(String jti) {
        return jti != null && !jti.isBlank() && revokedAccessTokenRepository.existsByJti(jti);
    }

    /** Revokes an access token until it would have expired anyway. Idempotent. */
    @Transactional
    public void revoke(String jti, Long userId, Instant expiresAt) {
        if (jti == null || jti.isBlank() || expiresAt == null) {
            return;
        }
        if (revokedAccessTokenRepository.existsByJti(jti)) {
            return;
        }
        revokedAccessTokenRepository.save(RevokedAccessToken.builder()
                .jti(jti)
                .userId(userId)
                .expiresAt(expiresAt)
                .revokedAt(Instant.now())
                .build());
    }

    /**
     * Drops revocations whose token has expired: they can no longer be presented, so keeping them
     * would only grow the table without changing any decision.
     */
    @Transactional
    public int purgeExpired() {
        return revokedAccessTokenRepository.deleteExpired(Instant.now());
    }
}
