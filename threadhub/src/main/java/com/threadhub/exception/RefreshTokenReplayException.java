package com.threadhub.exception;

/**
 * Internal control-flow signal: a refresh token that had already been consumed — or that lost a
 * concurrent rotation race — was presented again.
 *
 * <p>It carries the affected token family so the caller can revoke that lineage. The caller must do
 * so from <em>outside</em> the transaction that detected the replay: revoking inside it would be
 * rolled back by the very rejection that detected it, and nesting a second transaction inside the
 * failing one can deadlock against its own suspended sibling.</p>
 *
 * <p>This never reaches an HTTP response; it is translated into {@link InvalidRefreshTokenException}.
 * The message deliberately omits the family identifier so nothing internal is logged.</p>
 */
public class RefreshTokenReplayException extends RuntimeException {

    private final transient String familyId;

    public RefreshTokenReplayException(String familyId) {
        super("Refresh token replay detected");
        this.familyId = familyId;
    }

    public String getFamilyId() {
        return familyId;
    }
}
