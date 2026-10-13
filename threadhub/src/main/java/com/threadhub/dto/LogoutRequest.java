package com.threadhub.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Body for {@code POST /api/auth/logout}. The refresh token is optional: when supplied, its whole
 * token family is revoked, so the session cannot be refreshed again. The caller's access token is
 * always taken from the {@code Authorization} header and revoked as well.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LogoutRequest {

    private String refreshToken;
}
