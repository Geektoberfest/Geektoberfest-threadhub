package com.threadhub.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Credentials returned by login and refresh.
 *
 * <p>{@code expiresIn} is the <strong>access</strong>-token lifetime in <strong>milliseconds</strong>,
 * which is the unit this endpoint has always used (it previously carried 86400000 for one day). The
 * refresh-token lifetime is intentionally not echoed here; it is fixed at seven days by
 * {@code jwt.refresh-token-expiration}.</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuthResponse {

    /** Short-lived bearer token for API calls. */
    private String accessToken;

    /** Opaque, single-use token for POST /api/auth/refresh. Never a JWT. */
    private String refreshToken;

    private String tokenType;

    /** Access-token lifetime in milliseconds. */
    private Long expiresIn;

    private UserResponse user;
}
