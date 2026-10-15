package com.threadhub.service;

import com.threadhub.dto.AuthResponse;
import com.threadhub.dto.LoginRequest;
import com.threadhub.dto.RefreshTokenRequest;
import com.threadhub.dto.UserResponse;
import com.threadhub.exception.InvalidCredentialsException;
import com.threadhub.model.RefreshToken;
import com.threadhub.model.User;
import com.threadhub.repository.UserRepository;
import com.threadhub.security.JwtService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;

    public AuthService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService,
                       RefreshTokenService refreshTokenService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.refreshTokenService = refreshTokenService;
    }

    @Transactional
    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new InvalidCredentialsException("Invalid email or password"));

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new InvalidCredentialsException("Invalid email or password");
        }

        String accessToken = jwtService.generateToken(user);
        RefreshToken refreshToken = refreshTokenService.createRefreshToken(user);

        return AuthResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken.getToken())
                .tokenType("Bearer")
                .expiresIn(jwtService.getExpirationTimeMs())
                .user(UserResponse.fromEntity(user))
                .build();
    }

    @Transactional
    public AuthResponse refreshToken(RefreshTokenRequest request) {
        RefreshToken newRefreshToken = refreshTokenService.verifyAndRotate(request.getRefreshToken());
        User user = newRefreshToken.getUser();
        String newAccessToken = jwtService.generateToken(user);

        return AuthResponse.builder()
                .accessToken(newAccessToken)
                .refreshToken(newRefreshToken.getToken())
                .tokenType("Bearer")
                .expiresIn(jwtService.getExpirationTimeMs())
                .user(UserResponse.fromEntity(user))
                .build();
    }

    @Transactional
    public void logout(RefreshTokenRequest request) {
        if (request != null && request.getRefreshToken() != null) {
            refreshTokenService.revokeToken(request.getRefreshToken());
        }
    }
}