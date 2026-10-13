package com.threadhub.security;

import com.threadhub.service.AccessTokenBlacklistService;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Rejects access tokens whose {@code jti} has been revoked, so a logged-out token stops working
 * even though its signature and expiry are still valid.
 *
 * <p>Composed into the existing {@code JwtDecoder} alongside the default timestamp validator, which
 * keeps revocation enforcement inside the standard resource-server pipeline rather than a parallel
 * authentication mechanism.</p>
 */
public class AccessTokenRevocationValidator implements OAuth2TokenValidator<Jwt> {

    private final AccessTokenBlacklistService accessTokenBlacklistService;

    public AccessTokenRevocationValidator(AccessTokenBlacklistService accessTokenBlacklistService) {
        this.accessTokenBlacklistService = accessTokenBlacklistService;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        if (accessTokenBlacklistService.isRevoked(token.getId())) {
            return OAuth2TokenValidatorResult.failure(new OAuth2Error(
                    OAuth2ErrorCodes.INVALID_TOKEN,
                    "The access token has been revoked",
                    null));
        }
        return OAuth2TokenValidatorResult.success();
    }
}
