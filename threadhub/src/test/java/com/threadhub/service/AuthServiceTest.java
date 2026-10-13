package com.threadhub.service;

import com.threadhub.dto.AuthResponse;
import com.threadhub.dto.LoginRequest;
import com.threadhub.exception.InvalidCredentialsException;
import com.threadhub.model.User;
import com.threadhub.repository.UserRepository;
import com.threadhub.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtService jwtService;

    @Mock
    private RefreshTokenService refreshTokenService;

    @Mock
    private AccessTokenBlacklistService accessTokenBlacklistService;

    @InjectMocks
    private AuthService authService;

    private User sampleUser;
    private LoginRequest loginRequest;

    @BeforeEach
    void setUp() {
        loginRequest = LoginRequest.builder()
                .email("user@example.com")
                .password("rawPassword123")
                .build();

        sampleUser = User.builder()
                .id(10L)
                .username("testuser")
                .email("user@example.com")
                .password("encodedPassword123")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    @Test
    @DisplayName("Should successfully authenticate user and return AuthResponse with JWT")
    void shouldLoginSuccessfully() {
        when(userRepository.findByEmail(loginRequest.getEmail())).thenReturn(Optional.of(sampleUser));
        when(passwordEncoder.matches("rawPassword123", "encodedPassword123")).thenReturn(true);
        when(jwtService.generateToken(sampleUser)).thenReturn("mocked.jwt.token");
        when(jwtService.getExpirationTimeMs()).thenReturn(900_000L);
        when(refreshTokenService.startSession(sampleUser)).thenReturn(
                new RefreshTokenService.IssuedRefreshToken("mocked-refresh-token", Instant.now().plusSeconds(604_800)));

        AuthResponse response = authService.login(loginRequest);

        assertThat(response).isNotNull();
        assertThat(response.getAccessToken()).isEqualTo("mocked.jwt.token");
        assertThat(response.getTokenType()).isEqualTo("Bearer");
        assertThat(response.getExpiresIn()).isEqualTo(900_000L);
        assertThat(response.getRefreshToken()).isEqualTo("mocked-refresh-token");
        assertThat(response.getUser()).isNotNull();
        assertThat(response.getUser().getEmail()).isEqualTo("user@example.com");

        verify(jwtService, times(1)).generateToken(sampleUser);
        verify(refreshTokenService, times(1)).startSession(sampleUser);
    }

    @Test
    @DisplayName("Should throw InvalidCredentialsException when email is not found")
    void shouldThrowExceptionWhenEmailNotFound() {
        when(userRepository.findByEmail(loginRequest.getEmail())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(loginRequest))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessageContaining("Invalid email or password");

        verify(jwtService, never()).generateToken(any());
    }

    @Test
    @DisplayName("Should throw InvalidCredentialsException when password does not match")
    void shouldThrowExceptionWhenPasswordMismatch() {
        when(userRepository.findByEmail(loginRequest.getEmail())).thenReturn(Optional.of(sampleUser));
        when(passwordEncoder.matches("rawPassword123", "encodedPassword123")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(loginRequest))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessageContaining("Invalid email or password");

        verify(jwtService, never()).generateToken(any());
    }
}
