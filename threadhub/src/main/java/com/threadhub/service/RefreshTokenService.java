package com.threadhub.service;

import com.threadhub.exception.InvalidRefreshTokenException;
import com.threadhub.exception.RefreshTokenReplayException;
import com.threadhub.model.RefreshToken;
import com.threadhub.model.User;
import com.threadhub.repository.RefreshTokenRepository;
import com.threadhub.security.TokenHashes;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/**
 * Issues and rotates refresh tokens.
 *
 * <p>Refresh tokens are opaque 256-bit random strings. Only the SHA-256 digest is persisted, so a
 * leaked database snapshot does not contain usable credentials and raw values never reach the logs.
 * Each successful rotation consumes the presented token and issues its replacement in the same
 * family; presenting an already-consumed token is treated as replay and revokes the whole family.
 * Replay policy: because a lost rotation race is indistinguishable from replay at this layer, a
 * concurrent duplicate refresh also revokes the family. Two requests can therefore never both
 * succeed, at the cost of the client needing to sign in again after such a race. Replay is reported
 * with {@link RefreshTokenReplayException} and the revocation is left to the caller, because it has
 * to be committed outside this transaction (see {@link RefreshTokenFamilyRevoker}).</p>
 */
@Service
public class RefreshTokenService {

    /** 256 bits of entropy, per OWASP guidance for opaque session/token identifiers. */
    private static final int TOKEN_BYTES = 32;

    private final RefreshTokenRepository refreshTokenRepository;
    private final SecureRandom secureRandom = new SecureRandom();
    private final long refreshTokenTtlMs;

    public RefreshTokenService(RefreshTokenRepository refreshTokenRepository,
                               @Value("${jwt.refresh-token-expiration:604800000}") long refreshTokenTtlMs) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.refreshTokenTtlMs = refreshTokenTtlMs;
    }

    /** Starts a brand new token family, for example on login. */
    @Transactional
    public IssuedRefreshToken startSession(User user) {
        Instant now = Instant.now();
        String rawToken = generateRawToken();
        RefreshToken token = refreshTokenRepository.save(RefreshToken.builder()
                .user(user)
                .tokenHash(TokenHashes.sha256Hex(rawToken))
                .familyId(UUID.randomUUID().toString())
                .issuedAt(now)
                .expiresAt(now.plusMillis(refreshTokenTtlMs))
                .build());
        return new IssuedRefreshToken(rawToken, token.getExpiresAt());
    }

    /**
     * Exchanges a refresh token for a replacement, exactly once.
     *
     * @throws InvalidRefreshTokenException if the token is unknown, expired, revoked or replayed
     */
    @Transactional
    public RotatedRefreshToken rotate(String presentedRawToken) {
        if (presentedRawToken == null || presentedRawToken.isBlank()) {
            throw new InvalidRefreshTokenException("Refresh token is missing");
        }

        Instant now = Instant.now();
        RefreshToken presented = refreshTokenRepository
                .findByTokenHash(TokenHashes.sha256Hex(presentedRawToken))
                .orElseThrow(() -> new InvalidRefreshTokenException("Refresh token is not recognised"));

        // Read everything needed up front: the consume below clears the persistence context.
        User owner = presented.getUser();
        String familyId = presented.getFamilyId();
        Long presentedId = presented.getId();

        if (presented.getRevokedAt() != null) {
            throw new InvalidRefreshTokenException("Refresh token is no longer valid");
        }
        if (!presented.getExpiresAt().isAfter(now)) {
            throw new InvalidRefreshTokenException("Refresh token is no longer valid");
        }
        if (presented.getConsumedAt() != null) {
            // Replay of an already-rotated token. The lineage is revoked by the caller, outside this
            // transaction, so the revocation survives the rejection that follows.
            throw new RefreshTokenReplayException(familyId);
        }

        String replacementRawToken = generateRawToken();
        String replacementHash = TokenHashes.sha256Hex(replacementRawToken);

        // The single conditional UPDATE is the only thing that decides who may rotate.
        if (refreshTokenRepository.consumeIfActive(presentedId, now, replacementHash) != 1) {
            // Lost a concurrent rotation race; treat it exactly like replay.
            throw new RefreshTokenReplayException(familyId);
        }

        RefreshToken replacement = refreshTokenRepository.save(RefreshToken.builder()
                .user(owner)
                .tokenHash(replacementHash)
                .familyId(familyId)
                .issuedAt(now)
                .expiresAt(now.plusMillis(refreshTokenTtlMs))
                .build());

        return new RotatedRefreshToken(owner, replacementRawToken, replacement.getExpiresAt());
    }

    /**
     * Revokes the presented token and its whole family, but only when the token belongs to
     * {@code expectedUserId}. Unknown or foreign tokens are ignored so logout can never revoke
     * another user's session. Safe to call repeatedly.
     *
     * @return true when a token owned by the caller was found
     */
    @Transactional
    public boolean revokeSession(String presentedRawToken, Long expectedUserId) {
        if (presentedRawToken == null || presentedRawToken.isBlank()) {
            return false;
        }
        Optional<RefreshToken> owned = refreshTokenRepository
                .findByTokenHash(TokenHashes.sha256Hex(presentedRawToken))
                .filter(token -> expectedUserId == null || expectedUserId.equals(token.getUser().getId()));

        owned.ifPresent(token -> refreshTokenRepository.revokeFamily(token.getFamilyId(), Instant.now()));
        return owned.isPresent();
    }

    /** Deletes refresh tokens that have already expired; they can no longer be exchanged. */
    @Transactional
    public int purgeExpired() {
        return refreshTokenRepository.deleteExpired(Instant.now());
    }

    public long getRefreshTokenTtlMs() {
        return refreshTokenTtlMs;
    }

    private String generateRawToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** A freshly issued refresh token; only {@code rawToken} is ever returned to the client. */
    public record IssuedRefreshToken(String rawToken, Instant expiresAt) {
    }

    /** The replacement credentials produced by a successful rotation. */
    public record RotatedRefreshToken(User user, String rawToken, Instant expiresAt) {
    }
}
