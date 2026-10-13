package com.threadhub.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * An access token that has been revoked before its natural expiry, identified by its {@code jti}
 * claim.
 *
 * <p>Revocations are persisted rather than kept in a process-local set so that every application
 * instance observes the same decision. A row is only needed until the token itself expires, after
 * which the signature/expiry validation rejects it anyway; {@code expiresAt} records that moment so
 * expired rows can be purged.</p>
 */
@Entity
@Table(name = "revoked_access_tokens", indexes = {
        @Index(name = "idx_revoked_access_tokens_jti", columnList = "jti", unique = true),
        @Index(name = "idx_revoked_access_tokens_expires_at", columnList = "expires_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RevokedAccessToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Wider than the 36 characters of a UUID so a future identifier format cannot break logout.
    @Column(name = "jti", nullable = false, unique = true, length = 64)
    private String jti;

    @Column(name = "user_id")
    private Long userId;

    /** Moment the revoked access token would have expired; used to purge the entry. */
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at", nullable = false)
    private Instant revokedAt;
}
