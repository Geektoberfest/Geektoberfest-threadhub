package com.threadhub.service;

import com.threadhub.repository.RefreshTokenRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Revokes a refresh-token family in its own transaction.
 *
 * <p>Replay detection has to survive the rejection that detects it. The caller responds to a replayed
 * token by throwing, and any revocation made on the same unit of work would be rolled back by that
 * very exception — leaving the replayed token's family usable. Committing the revocation
 * independently makes the security response durable even though the request fails.</p>
 *
 * <p>The caller must not already be inside a transaction: revoking while the failing transaction is
 * still suspended (as {@code REQUIRES_NEW} would do) lets this transaction contend for rows its own
 * sibling is holding, which can wedge both. It is also deliberately a separate bean, because a
 * self-invocation would bypass the transactional proxy entirely.</p>
 */
@Component
public class RefreshTokenFamilyRevoker {

    private final RefreshTokenRepository refreshTokenRepository;

    public RefreshTokenFamilyRevoker(RefreshTokenRepository refreshTokenRepository) {
        this.refreshTokenRepository = refreshTokenRepository;
    }

    /** @return the number of still-active tokens that were revoked */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int revokeFamily(String familyId) {
        return refreshTokenRepository.revokeFamily(familyId, Instant.now());
    }
}
