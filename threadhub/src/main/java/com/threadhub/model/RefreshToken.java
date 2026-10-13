package com.threadhub.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * A persisted refresh token.
 *
 * <p>The raw token value is never stored: only its SHA-256 digest ({@code tokenHash}). Tokens are
 * grouped into a <em>family</em> ({@code familyId}) that is created on login and inherited by every
 * rotation, so that replaying a previously rotated token can revoke the entire lineage.</p>
 */
@Entity
@Table(name = "refresh_tokens", indexes = {
        @Index(name = "idx_refresh_tokens_token_hash", columnList = "token_hash", unique = true),
        @Index(name = "idx_refresh_tokens_family_id", columnList = "family_id"),
        @Index(name = "idx_refresh_tokens_user_id", columnList = "user_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "family_id", nullable = false, length = 36)
    private String familyId;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** Set when the token has been exchanged. A second use of a consumed token is a replay. */
    @Column(name = "consumed_at")
    private Instant consumedAt;

    /** Set when the token — or its whole family — has been revoked, for example by logout. */
    @Column(name = "revoked_at")
    private Instant revokedAt;

    /** Digest of the token that replaced this one during rotation; supports lineage auditing. */
    @Column(name = "replaced_by_hash", length = 64)
    private String replacedByHash;

    /** True while the token may still be exchanged: unconsumed, unrevoked and unexpired. */
    public boolean usableAt(Instant now) {
        return consumedAt == null && revokedAt == null && expiresAt.isAfter(now);
    }
}
