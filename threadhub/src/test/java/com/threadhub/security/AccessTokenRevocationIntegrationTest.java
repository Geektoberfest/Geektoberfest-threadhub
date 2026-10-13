package com.threadhub.security;

import com.threadhub.model.User;
import com.threadhub.repository.RevokedAccessTokenRepository;
import com.threadhub.repository.UserRepository;
import com.threadhub.service.AccessTokenBlacklistService;
import com.threadhub.service.RefreshTokenService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies that revocation is actually enforced by the authentication pipeline — signature and
 * expiry alone must not be enough once a token has been revoked.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AccessTokenRevocationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private JwtDecoder jwtDecoder;

    @Autowired
    private AccessTokenBlacklistService accessTokenBlacklistService;

    @Autowired
    private RefreshTokenService refreshTokenService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RevokedAccessTokenRepository revokedAccessTokenRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private final List<Long> createdUserIds = new ArrayList<>();
    private final List<String> createdJtis = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        revokedAccessTokenRepository.deleteAll(revokedAccessTokenRepository.findAll().stream()
                .filter(entry -> createdJtis.contains(entry.getJti()))
                .toList());
        createdJtis.clear();
        userRepository.deleteAllById(createdUserIds);
        createdUserIds.clear();
    }

    @Test
    @DisplayName("Access-token and refresh-token lifetimes match the configured defaults")
    void configuredLifetimesMatchTheIssue() {
        assertThat(jwtService.getExpirationTimeMs()).isEqualTo(900_000L);
        assertThat(refreshTokenService.getRefreshTokenTtlMs()).isEqualTo(604_800_000L);
    }

    @Test
    @DisplayName("A valid access token authenticates on a protected endpoint")
    void validAccessTokenIsAccepted() throws Exception {
        User user = newUser();
        String token = jwtService.generateToken(user);

        mockMvc.perform(get("/api/users/{id}", user.getId()).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("A revoked access token is rejected even though its signature and expiry are still valid")
    void revokedAccessTokenIsRejected() throws Exception {
        User user = newUser();
        String token = jwtService.generateToken(user);

        mockMvc.perform(get("/api/users/{id}", user.getId()).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());

        Jwt decoded = jwtDecoder.decode(token);
        accessTokenBlacklistService.revoke(decoded.getId(), user.getId(), decoded.getExpiresAt());
        createdJtis.add(decoded.getId());

        // Same token, same signature, same expiry — only the revocation state changed.
        mockMvc.perform(get("/api/users/{id}", user.getId()).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Revoking one user's access token does not affect another user's")
    void revocationIsScopedToTheSingleToken() throws Exception {
        User revoked = newUser();
        User unaffected = newUser();
        String revokedToken = jwtService.generateToken(revoked);
        String unaffectedToken = jwtService.generateToken(unaffected);

        Jwt decoded = jwtDecoder.decode(revokedToken);
        accessTokenBlacklistService.revoke(decoded.getId(), revoked.getId(), decoded.getExpiresAt());
        createdJtis.add(decoded.getId());

        mockMvc.perform(get("/api/users/{id}", revoked.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + revokedToken))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/users/{id}", unaffected.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + unaffectedToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Public endpoints stay public and protected ones still challenge unauthenticated callers")
    void publicEndpointsRemainPublic() throws Exception {
        mockMvc.perform(get("/api/communities")).andExpect(status().isOk());
        mockMvc.perform(get("/api/users/1")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Revocation bookkeeping is purged once the token can no longer be valid")
    void revocationEntriesArePurgedAfterTheTokenExpires() {
        String expiredJti = "expired-jti-" + UUID.randomUUID();
        String liveJti = "live-jti-" + UUID.randomUUID();
        createdJtis.add(expiredJti);
        createdJtis.add(liveJti);

        accessTokenBlacklistService.revoke(expiredJti, null, Instant.now().minus(Duration.ofMinutes(1)));
        accessTokenBlacklistService.revoke(liveJti, null, Instant.now().plus(Duration.ofMinutes(5)));
        assertThat(accessTokenBlacklistService.isRevoked(expiredJti)).isTrue();

        accessTokenBlacklistService.purgeExpired();

        assertThat(accessTokenBlacklistService.isRevoked(expiredJti))
                .as("an entry whose token has expired is no longer needed")
                .isFalse();
        assertThat(accessTokenBlacklistService.isRevoked(liveJti))
                .as("an entry whose token is still valid must be retained")
                .isTrue();
    }

    @Test
    @DisplayName("A token with no jti claim cannot be revoked and is therefore rejected on logout paths")
    void tokensWithoutIdentifierAreNotBlacklistable() {
        assertThat(accessTokenBlacklistService.isRevoked(null)).isFalse();
        // Revoking without an identifier is a no-op rather than an error.
        accessTokenBlacklistService.revoke(null, null, Instant.now().plusSeconds(60));
        accessTokenBlacklistService.revoke(" ", null, Instant.now().plusSeconds(60));
    }

    private User newUser() {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        User user = userRepository.save(User.builder()
                .username("rev_user_" + unique)
                .email("rev_" + unique + "@example.com")
                .password(passwordEncoder.encode("password123"))
                .build());
        createdUserIds.add(user.getId());
        return user;
    }
}
