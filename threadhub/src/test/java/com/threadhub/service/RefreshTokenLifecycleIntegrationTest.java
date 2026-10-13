package com.threadhub.service;

import com.threadhub.dto.AuthResponse;
import com.threadhub.dto.LoginRequest;
import com.threadhub.exception.InvalidRefreshTokenException;
import com.threadhub.model.RefreshToken;
import com.threadhub.model.User;
import com.threadhub.repository.RefreshTokenRepository;
import com.threadhub.repository.UserRepository;
import com.threadhub.security.TokenHashes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the refresh-token lifecycle against a real database, which is the only way to verify the
 * atomic single-use rotation and the family-wide revocation it triggers.
 */
@SpringBootTest
class RefreshTokenLifecycleIntegrationTest {

    private static final long SEVEN_DAYS_MS = 604_800_000L;
    private static final long FIFTEEN_MINUTES_MS = 900_000L;

    @Autowired
    private AuthService authService;

    @Autowired
    private RefreshTokenService refreshTokenService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtDecoder jwtDecoder;

    private final List<Long> createdUserIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (Long userId : createdUserIds) {
            refreshTokenRepository.deleteAll(refreshTokenRepository.findByUserId(userId));
            userRepository.deleteById(userId);
        }
        createdUserIds.clear();
    }

    @Test
    @DisplayName("Login issues a 15-minute access token and a seven-day opaque refresh token stored only as a hash")
    void loginIssuesAccessAndRefreshTokens() {
        User user = newUser("password123");

        AuthResponse response = login(user);

        assertThat(response.getAccessToken()).isNotBlank();
        assertThat(response.getRefreshToken()).isNotBlank();
        assertThat(response.getTokenType()).isEqualTo("Bearer");
        assertThat(response.getExpiresIn()).isEqualTo(FIFTEEN_MINUTES_MS);
        assertThat(response.getUser().getEmail()).isEqualTo(user.getEmail());

        // Opaque, not a JWT: a refresh token must not be self-describing or verifiable offline.
        assertThat(response.getRefreshToken()).doesNotContain(".");

        // The raw value is never persisted; only its digest is.
        assertThat(refreshTokenRepository.findByTokenHash(response.getRefreshToken())).isEmpty();

        RefreshToken stored = refreshTokenRepository
                .findByTokenHash(TokenHashes.sha256Hex(response.getRefreshToken()))
                .orElseThrow();
        assertThat(stored.getUser().getId()).isEqualTo(user.getId());
        assertThat(stored.getConsumedAt()).isNull();
        assertThat(stored.getRevokedAt()).isNull();

        long remaining = Duration.between(Instant.now(), stored.getExpiresAt()).toMillis();
        assertThat(remaining).isBetween(SEVEN_DAYS_MS - 10_000L, SEVEN_DAYS_MS);

        Jwt accessToken = jwtDecoder.decode(response.getAccessToken());
        assertThat(Duration.between(accessToken.getIssuedAt(), accessToken.getExpiresAt()).toMillis())
                .isEqualTo(FIFTEEN_MINUTES_MS);
    }

    @Test
    @DisplayName("Rotating a refresh token issues new credentials and retires the presented token")
    void rotationRetiresThePresentedToken() {
        User user = newUser("password123");
        AuthResponse first = login(user);

        AuthResponse second = authService.refresh(refreshRequest(first.getRefreshToken()));

        assertThat(second.getAccessToken()).isNotEqualTo(first.getAccessToken());
        assertThat(second.getRefreshToken()).isNotEqualTo(first.getRefreshToken());
        assertThat(second.getUser().getId()).isEqualTo(user.getId());

        RefreshToken used = refreshTokenRepository
                .findByTokenHash(TokenHashes.sha256Hex(first.getRefreshToken()))
                .orElseThrow();
        assertThat(used.getConsumedAt()).isNotNull();
        assertThat(used.getReplacedByHash()).isEqualTo(TokenHashes.sha256Hex(second.getRefreshToken()));

        // The replacement continues the same family.
        RefreshToken replacement = refreshTokenRepository
                .findByTokenHash(TokenHashes.sha256Hex(second.getRefreshToken()))
                .orElseThrow();
        assertThat(replacement.getFamilyId()).isEqualTo(used.getFamilyId());

        // The new token works, which also proves the presented one is no longer needed.
        assertThat(authService.refresh(refreshRequest(second.getRefreshToken())).getAccessToken()).isNotBlank();
    }

    @Test
    @DisplayName("Replaying a rotated refresh token revokes the whole family, including the replacement")
    void replayingARotatedTokenRevokesTheFamily() {
        User user = newUser("password123");
        AuthResponse first = login(user);
        AuthResponse second = authService.refresh(refreshRequest(first.getRefreshToken()));

        // A stolen copy of the already-rotated token is replayed.
        assertThatThrownBy(() -> authService.refresh(refreshRequest(first.getRefreshToken())))
                .isInstanceOf(InvalidRefreshTokenException.class);

        // Replay detection must invalidate the legitimately issued replacement too.
        assertThatThrownBy(() -> authService.refresh(refreshRequest(second.getRefreshToken())))
                .isInstanceOf(InvalidRefreshTokenException.class);
        assertThat(refreshTokenRepository.findByUserId(user.getId()))
                .allSatisfy(token -> assertThat(token.getRevokedAt()).isNotNull());
    }

    @Test
    @DisplayName("Unknown, expired and revoked refresh tokens are rejected")
    void invalidRefreshTokensAreRejected() {
        User user = newUser("password123");
        AuthResponse session = login(user);

        assertThatThrownBy(() -> authService.refresh(refreshRequest("not-a-real-token")))
                .isInstanceOf(InvalidRefreshTokenException.class);

        // An expired-but-otherwise-valid token.
        String expiredRaw = "expired-" + UUID.randomUUID();
        refreshTokenRepository.save(RefreshToken.builder()
                .user(user)
                .tokenHash(TokenHashes.sha256Hex(expiredRaw))
                .familyId(UUID.randomUUID().toString())
                .issuedAt(Instant.now().minus(Duration.ofDays(8)))
                .expiresAt(Instant.now().minus(Duration.ofDays(1)))
                .build());
        assertThatThrownBy(() -> authService.refresh(refreshRequest(expiredRaw)))
                .isInstanceOf(InvalidRefreshTokenException.class);

        // A revoked (logged-out) session.
        authService.logout(session.getRefreshToken(), null, user.getId(), Instant.now().plusSeconds(60));
        assertThatThrownBy(() -> authService.refresh(refreshRequest(session.getRefreshToken())))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    @DisplayName("Logout revokes the refresh session and is safe to repeat")
    void logoutRevokesSessionAndIsRepeatable() {
        User user = newUser("password123");
        AuthResponse session = login(user);

        authService.logout(session.getRefreshToken(), "jti-" + UUID.randomUUID(), user.getId(),
                Instant.now().plusSeconds(900));

        assertThat(refreshTokenRepository.findByUserId(user.getId()))
                .allSatisfy(token -> assertThat(token.getRevokedAt()).isNotNull());

        // Repeating logout must not fail.
        authService.logout(session.getRefreshToken(), "jti-" + UUID.randomUUID(), user.getId(),
                Instant.now().plusSeconds(900));
        assertThatThrownBy(() -> authService.refresh(refreshRequest(session.getRefreshToken())))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    @DisplayName("Logout only revokes the caller's own refresh session")
    void logoutDoesNotRevokeAnotherUsersSession() {
        User owner = newUser("password123");
        User other = newUser("password123");
        AuthResponse ownerSession = login(owner);

        // "other" tries to log out with a token that is not theirs.
        authService.logout(ownerSession.getRefreshToken(), "jti-" + UUID.randomUUID(), other.getId(),
                Instant.now().plusSeconds(900));

        assertThat(refreshTokenRepository.findByTokenHash(TokenHashes.sha256Hex(ownerSession.getRefreshToken())))
                .get()
                .satisfies(token -> assertThat(token.getRevokedAt()).isNull());
        assertThat(authService.refresh(refreshRequest(ownerSession.getRefreshToken())).getAccessToken()).isNotBlank();
    }

    @Test
    @DisplayName("A refresh token only ever yields credentials for its owning user")
    void refreshIsBoundToTheTokenOwner() {
        User alice = newUser("password123");
        User bob = newUser("password123");
        AuthResponse aliceSession = login(alice);
        login(bob);

        AuthResponse refreshed = authService.refresh(refreshRequest(aliceSession.getRefreshToken()));

        Jwt accessToken = jwtDecoder.decode(refreshed.getAccessToken());
        assertThat(accessToken.getSubject()).isEqualTo(alice.getId().toString());
        assertThat(accessToken.getSubject()).isNotEqualTo(bob.getId().toString());
        assertThat(refreshed.getUser().getId()).isEqualTo(alice.getId());
    }

    @Test
    @DisplayName("Concurrent refreshes with the same token cannot both succeed")
    void concurrentRefreshesCannotBothSucceed() throws Exception {
        User user = newUser("password123");
        AuthResponse session = login(user);

        int attempts = 2;
        ExecutorService executor = Executors.newFixedThreadPool(attempts);
        CountDownLatch startTogether = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger rejections = new AtomicInteger();

        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < attempts; i++) {
                futures.add(executor.submit(() -> {
                    await(startTogether);
                    try {
                        authService.refresh(refreshRequest(session.getRefreshToken()));
                        successes.incrementAndGet();
                    } catch (InvalidRefreshTokenException expected) {
                        rejections.incrementAndGet();
                    }
                }));
            }
            startTogether.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(successes.get()).as("exactly one concurrent rotation may win").isEqualTo(1);
        assertThat(rejections.get()).isEqualTo(attempts - 1);
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await(20, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting to start", e);
        }
    }

    private AuthResponse login(User user) {
        return authService.login(LoginRequest.builder()
                .email(user.getEmail())
                .password("password123")
                .build());
    }

    private com.threadhub.dto.RefreshTokenRequest refreshRequest(String refreshToken) {
        return com.threadhub.dto.RefreshTokenRequest.builder().refreshToken(refreshToken).build();
    }

    private User newUser(String rawPassword) {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        User user = userRepository.save(User.builder()
                .username("it_user_" + unique)
                .email("it_" + unique + "@example.com")
                .password(passwordEncoder.encode(rawPassword))
                .build());
        createdUserIds.add(user.getId());
        return user;
    }
}
