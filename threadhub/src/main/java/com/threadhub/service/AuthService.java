package com.threadhub.service;

import com.threadhub.dto.AuthResponse;
import com.threadhub.dto.LoginRequest;
import com.threadhub.dto.RefreshTokenRequest;
import com.threadhub.dto.UserResponse;
import com.threadhub.exception.InvalidCredentialsException;
import com.threadhub.exception.InvalidRefreshTokenException;
import com.threadhub.exception.RefreshTokenReplayException;
import com.threadhub.model.User;
import com.threadhub.repository.UserRepository;
import com.threadhub.security.JwtService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
@Transactional(readOnly = true)
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final RefreshTokenFamilyRevoker refreshTokenFamilyRevoker;
    private final AccessTokenBlacklistService accessTokenBlacklistService;

    public AuthService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService,
                       RefreshTokenService refreshTokenService,
                       RefreshTokenFamilyRevoker refreshTokenFamilyRevoker,
                       AccessTokenBlacklistService accessTokenBlacklistService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.refreshTokenService = refreshTokenService;
        this.refreshTokenFamilyRevoker = refreshTokenFamilyRevoker;
        this.accessTokenBlacklistService = accessTokenBlacklistService;
    }

    /** Verifies credentials and starts a new session: a 15-minute access token plus a 7-day refresh token. */
    @Transactional
    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new InvalidCredentialsException("Invalid email or password"));

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new InvalidCredentialsException("Invalid email or password");
        }

        RefreshTokenService.IssuedRefreshToken refreshToken = refreshTokenService.startSession(user);

        return AuthResponse.builder()
                .accessToken(jwtService.generateToken(user))
                .refreshToken(refreshToken.rawToken())
                .tokenType("Bearer")
                .expiresIn(jwtService.getExpirationTimeMs())
                .user(UserResponse.fromEntity(user))
                .build();
    }

    /**
     * Exchanges a refresh token for a new access token and a replacement refresh token. Ownership
     * comes from the persisted token record, never from the request body.
     *
     * <p>Deliberately runs without a transaction ({@link Propagation#NOT_SUPPORTED}) so that
     * {@link RefreshTokenService#rotate} owns its own short transaction and, when replay is
     * detected, the family revocation can be committed by a separate transaction rather than being
     * rolled back — or deadlocking against a suspended sibling — as part of this failing request.</p>
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public AuthResponse refresh(RefreshTokenRequest request) {
        RefreshTokenService.RotatedRefreshToken rotated;
        try {
            rotated = refreshTokenService.rotate(request.getRefreshToken());
        } catch (RefreshTokenReplayException replay) {
            // Burn the lineage durably, then reject the request.
            refreshTokenFamilyRevoker.revokeFamily(replay.getFamilyId());
            throw new InvalidRefreshTokenException("Refresh token is no longer valid");
        }

        return AuthResponse.builder()
                .accessToken(jwtService.generateToken(rotated.user()))
                .refreshToken(rotated.rawToken())
                .tokenType("Bearer")
                .expiresIn(jwtService.getExpirationTimeMs())
                .user(UserResponse.fromEntity(rotated.user()))
                .build();
    }

    /**
     * Ends the caller's session: revokes the presented refresh token's family and blacklists the
     * access token that authorised the request so it stops being accepted immediately.
     */
    @Transactional
    public void logout(String refreshToken, String accessTokenJti, Long userId, Instant accessTokenExpiresAt) {
        refreshTokenService.revokeSession(refreshToken, userId);
        accessTokenBlacklistService.revoke(accessTokenJti, userId, accessTokenExpiresAt);
    }
}
