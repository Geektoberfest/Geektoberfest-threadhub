package com.threadhub.repository;

import com.threadhub.model.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    /**
     * Looks a token up by digest, fetching its owner in the same query so the user is never a lazy
     * proxy that could be dereferenced outside the transaction.
     */
    @Query("select t from RefreshToken t join fetch t.user where t.tokenHash = :tokenHash")
    Optional<RefreshToken> findByTokenHash(@Param("tokenHash") String tokenHash);

    /**
     * Atomically consumes a token only if it is still unconsumed and unrevoked.
     *
     * <p>This single conditional UPDATE is the gate for rotation: under concurrent requests the row
     * is locked and re-evaluated, so exactly one caller can observe an update count of {@code 1}.
     * The result must be checked — an update count of {@code 0} means the token was already used
     * (replay or a lost race).</p>
     *
     * @return the number of rows updated: 1 when this caller won, 0 otherwise
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update RefreshToken t
               set t.consumedAt = :now, t.replacedByHash = :replacementHash
             where t.id = :id and t.consumedAt is null and t.revokedAt is null
            """)
    int consumeIfActive(@Param("id") Long id,
                        @Param("now") Instant now,
                        @Param("replacementHash") String replacementHash);

    /** Revokes every still-active token in a family; used on logout and on replay detection. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update RefreshToken t set t.revokedAt = :now where t.familyId = :familyId and t.revokedAt is null")
    int revokeFamily(@Param("familyId") String familyId, @Param("now") Instant now);

    /** Lists a user's refresh tokens, for example to enumerate their sessions. */
    List<RefreshToken> findByUserId(Long userId);

    /** Deletes tokens that are past their expiry so the table does not grow without bound. */
    @Modifying
    @Query("delete from RefreshToken t where t.expiresAt < :cutoff")
    int deleteExpired(@Param("cutoff") Instant cutoff);
}
