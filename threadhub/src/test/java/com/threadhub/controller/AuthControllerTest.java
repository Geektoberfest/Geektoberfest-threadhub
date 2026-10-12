package com.threadhub.controller;

import com.threadhub.config.RateLimitFilter;
import com.threadhub.config.SecurityConfig;
import com.threadhub.dto.AuthResponse;
import com.threadhub.dto.LoginRequest;
import com.threadhub.dto.UserResponse;
import com.threadhub.exception.GlobalExceptionHandler;
import com.threadhub.exception.InvalidCredentialsException;
import com.threadhub.service.AuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AuthController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class, RateLimitFilter.class})
@TestPropertySource(properties = {
        "jwt.secret=404E635266556A586E3272357538782F413F44284B6250645367566B5970",
        "rate-limit.login-requests-per-window=5",
        "rate-limit.registration-requests-per-window=5",
        "rate-limit.window-duration=PT1S"
})
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthService authService;

    private AuthResponse sampleAuthResponse;

    @BeforeEach
    void setUp() {
        UserResponse userResponse = UserResponse.builder()
                .id(1L)
                .username("jane_doe")
                .email("jane@example.com")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        sampleAuthResponse = AuthResponse.builder()
                .accessToken("mocked.jwt.token")
                .tokenType("Bearer")
                .expiresIn(86400000L)
                .user(userResponse)
                .build();
    }

    private String jsonRequest() {
        return """
                {
                    "email": "jane@example.com",
                    "password": "password123"
                }
                """;
    }

    private String invalidJsonRequest() {
        return """
                {
                    "email": "not-an-email",
                    "password": ""
                }
                """;
    }

    private String malformedJson() {
        return "{ \"email\": \"jane@example.com\", \"password\": }";
    }

    @Test
    @DisplayName("POST /api/auth/login - Should authenticate and return 200 OK with AuthResponse")
    void shouldLoginSuccessfully() throws Exception {
        when(authService.login(any(LoginRequest.class))).thenReturn(sampleAuthResponse);

        mockMvc.perform(post("/api/auth/login")
                        .header("X-Forwarded-For", "10.0.0.1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonRequest()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("mocked.jwt.token"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.user.username").value("jane_doe"))
                .andExpect(jsonPath("$.user.password").doesNotExist());
    }

    @Test
    @DisplayName("POST /api/auth/login - Should return 400 Bad Request when validation fails")
    void shouldReturnBadRequestWhenValidationFails() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .header("X-Forwarded-For", "10.0.0.2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidJsonRequest()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Validation failed"));
    }

    @Test
    @DisplayName("POST /api/auth/login - Should return 401 Unauthorized when credentials are invalid")
    void shouldReturnUnauthorizedWhenCredentialsInvalid() throws Exception {
        when(authService.login(any(LoginRequest.class)))
                .thenThrow(new InvalidCredentialsException("Invalid email or password"));

        mockMvc.perform(post("/api/auth/login")
                        .header("X-Forwarded-For", "10.0.0.3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonRequest()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value("Invalid email or password"));
    }

    @Test
    @DisplayName("POST /api/auth/login - Should return 400 Bad Request when JSON request body is malformed")
    void shouldReturnBadRequestWhenJsonIsMalformed() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .header("X-Forwarded-For", "10.0.0.4")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(malformedJson()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Malformed JSON request body"));
    }

    @Test
    @DisplayName("POST /api/auth/login - Should return 429 Too Many Requests when rate limit is exceeded")
    void shouldReturn429WhenRateLimitExceeded() throws Exception {
        when(authService.login(any(LoginRequest.class))).thenReturn(sampleAuthResponse);

        String clientIp = "10.10.10.1";

        // Exhaust the rate limit of 5 requests for this IP
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/auth/login")
                            .header("X-Forwarded-For", clientIp)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(jsonRequest()))
                    .andExpect(status().isOk());
        }

        // The 6th request from the same IP should be rate limited
        mockMvc.perform(post("/api/auth/login")
                        .header("X-Forwarded-For", clientIp)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonRequest()))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.error").value("Too Many Requests"))
                .andExpect(jsonPath("$.message").value("Rate limit exceeded. Please try again later."));
    }

    @Test
    @DisplayName("POST /api/auth/login - Rate limit should be scoped to client IP")
    void rateLimitShouldBeScopedToClientIp() throws Exception {
        when(authService.login(any(LoginRequest.class))).thenReturn(sampleAuthResponse);

        String exhaustedIp = "10.20.20.1";
        String freshIp = "10.20.20.2";

        // Exhaust rate limit for one IP
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/auth/login")
                            .header("X-Forwarded-For", exhaustedIp)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(jsonRequest()))
                    .andExpect(status().isOk());
        }

        // Different IP should still be allowed
        mockMvc.perform(post("/api/auth/login")
                        .header("X-Forwarded-For", freshIp)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonRequest()))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("POST /api/auth/login - OPTIONS preflight should not be rate limited")
    void optionsPreflightShouldNotBeRateLimited() throws Exception {
        when(authService.login(any(LoginRequest.class))).thenReturn(sampleAuthResponse);

        // Send a bad POST from one IP (consumes 1 token) and several OPTIONS from other IPs
        mockMvc.perform(post("/api/auth/login")
                        .header("X-Forwarded-For", "10.30.30.1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        // OPTIONS requests from different IPs should not consume tokens
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/auth/login")
                            .header("X-Forwarded-For", "10.30.30." + (i + 2))
                            .header("Origin", "http://localhost:5173")
                            .header("Access-Control-Request-Method", "POST")
                            .header("Access-Control-Request-Headers", "Content-Type"))
                    .andExpect(status().isBadRequest());
        }

        // A real POST from a fresh, unused IP should still work
        mockMvc.perform(post("/api/auth/login")
                        .header("X-Forwarded-For", "10.30.30.10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonRequest()))
                .andExpect(status().isOk());
    }
}
