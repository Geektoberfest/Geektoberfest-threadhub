package com.threadhub.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically removes token bookkeeping rows that can no longer affect any decision: access-token
 * revocations whose token has expired, and refresh tokens past their expiry.
 *
 * <p>Both purges are required for the tables not to grow without bound. Nothing here logs token
 * values, only counts.</p>
 */
@Component
public class TokenMaintenanceScheduler {

    private static final Logger log = LoggerFactory.getLogger(TokenMaintenanceScheduler.class);

    private final AccessTokenBlacklistService accessTokenBlacklistService;
    private final RefreshTokenService refreshTokenService;

    public TokenMaintenanceScheduler(AccessTokenBlacklistService accessTokenBlacklistService,
                                     RefreshTokenService refreshTokenService) {
        this.accessTokenBlacklistService = accessTokenBlacklistService;
        this.refreshTokenService = refreshTokenService;
    }

    @Scheduled(fixedDelayString = "${jwt.token-cleanup-interval:PT1H}",
            initialDelayString = "${jwt.token-cleanup-interval:PT1H}")
    public void purgeExpiredTokens() {
        int revokedAccessTokens = accessTokenBlacklistService.purgeExpired();
        int refreshTokens = refreshTokenService.purgeExpired();
        if (revokedAccessTokens > 0 || refreshTokens > 0) {
            log.info("Purged {} expired access-token revocations and {} expired refresh tokens",
                    revokedAccessTokens, refreshTokens);
        }
    }
}
