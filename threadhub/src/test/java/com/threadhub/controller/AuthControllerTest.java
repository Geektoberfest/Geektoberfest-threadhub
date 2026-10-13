package com.threadhub.controller;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.threadhub.config.SecurityConfig;
import com.threadhub.dto.AuthResponse;
import com.threadhub.dto.LoginRequest;
import com.threadhub.dto.RefreshTokenRequest;
import com.threadhub.dto.UserResponse;
import com.threadhub.exception.GlobalExceptionHandler;
import com.threadhub.exception.InvalidCredentialsException;
import com.threadhub.exception.InvalidRefreshTokenException;
import com.threadhub.service.AccessTokenBlacklistService;
import com.threadhub.service.AuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AuthController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
@TestPropertySource(properties = {
        "jwt.secret=404E635266556A586E3272357538782F413F44284B6250645367566B5970"
})
class AuthControllerTest {

    private static final String TEST_SECRET = "404E635266556A586E3272357538782F413F44284B6250645367566B5970";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthService authService;

    // Required by SecurityConfig, which now composes revocation checks into the JWT decoder.
    @MockitoBean
    private AccessTokenBlacklistService accessTokenBlacklistService;

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
                .refreshToken("mocked.refresh.token")
                .tokenType("Bearer")
                .expiresIn(900_000L)
                .user(userResponse)
                .build();
    }

    @Test
    @DisplayName("POST /api/auth/login - Should authenticate and return 200 OK with AuthResponse")
    void shouldLoginSuccessfully() throws Exception {
        when(authService.login(any(LoginRequest.class))).thenReturn(sampleAuthResponse);

        String jsonRequest = """
                {
                    "email": "jane@example.com",
                    "password": "password123"
                }
                """;

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonRequest))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("mocked.jwt.token"))
                .andExpect(jsonPath("$.refreshToken").value("mocked.refresh.token"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(900000))
                .andExpect(jsonPath("$.user.username").value("jane_doe"))
                .andExpect(jsonPath("$.user.password").doesNotExist());
    }

    @Test
    @DisplayName("POST /api/auth/login - Should return 400 Bad Request when validation fails")
    void shouldReturnBadRequestWhenValidationFails() throws Exception {
        String invalidJsonRequest = """
                {
                    "email": "not-an-email",
                    "password": ""
                }
                """;

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidJsonRequest))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Validation failed"));
    }

    @Test
    @DisplayName("POST /api/auth/login - Should return 401 Unauthorized when credentials are invalid")
    void shouldReturnUnauthorizedWhenCredentialsInvalid() throws Exception {
        when(authService.login(any(LoginRequest.class)))
                .thenThrow(new InvalidCredentialsException("Invalid email or password"));

        String jsonRequest = """
                {
                    "email": "jane@example.com",
                    "password": "wrongpassword"
                }
                """;

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonRequest))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value("Invalid email or password"));
    }

    @Test
    @DisplayName("POST /api/auth/login - Should return 400 Bad Request when JSON request body is malformed")
    void shouldReturnBadRequestWhenJsonIsMalformed() throws Exception {
        String malformedJson = "{ \"email\": \"jane@example.com\", \"password\": }";

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(malformedJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Malformed JSON request body"));
    }

    @Test
    @DisplayName("POST /api/auth/refresh - Should exchange a refresh token without a bearer token")
    void shouldRefreshTokensSuccessfully() throws Exception {
        when(authService.refresh(any(RefreshTokenRequest.class))).thenReturn(sampleAuthResponse);

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"presented-refresh-token\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("mocked.jwt.token"))
                .andExpect(jsonPath("$.refreshToken").value("mocked.refresh.token"));
    }

    @Test
    @DisplayName("POST /api/auth/refresh - Should return 401 when the refresh token is not usable")
    void shouldReturnUnauthorizedWhenRefreshTokenIsInvalid() throws Exception {
        when(authService.refresh(any(RefreshTokenRequest.class)))
                .thenThrow(new InvalidRefreshTokenException("Refresh token is no longer valid"));

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"stale-token\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.message").value("Refresh token is no longer valid"));
    }

    @Test
    @DisplayName("POST /api/auth/refresh - Should return 400 when the refresh token is blank")
    void shouldReturnBadRequestWhenRefreshTokenIsBlank() throws Exception {
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"));
    }

    @Test
    @DisplayName("POST /api/auth/logout - Should revoke the session for an authenticated caller")
    void shouldLogoutAuthenticatedCaller() throws Exception {
        mockMvc.perform(post("/api/auth/logout")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessTokenFor(1L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"presented-refresh-token\"}"))
                .andExpect(status().isNoContent());

        verify(authService).logout(eq("presented-refresh-token"), any(), eq(1L), any());
    }

    @Test
    @DisplayName("POST /api/auth/logout - Should still revoke the access token when no body is sent")
    void shouldLogoutWithoutBody() throws Exception {
        mockMvc.perform(post("/api/auth/logout")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessTokenFor(1L)))
                .andExpect(status().isNoContent());

        verify(authService).logout(eq(null), any(), eq(1L), any());
    }

    @Test
    @DisplayName("POST /api/auth/logout - Should reject unauthenticated callers with 401")
    void shouldRejectLogoutWithoutAccessToken() throws Exception {
        mockMvc.perform(post("/api/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"presented-refresh-token\"}"))
                .andExpect(status().isUnauthorized());
    }

    private String accessTokenFor(long userId) {
        SecretKey secretKey = new SecretKeySpec(TEST_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        JWKSource<SecurityContext> jwks = new ImmutableSecret<>(secretKey);
        JwtEncoder jwtEncoder = new NimbusJwtEncoder(jwks);

        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .id(UUID.randomUUID().toString())
                .subject(String.valueOf(userId))
                .claim("username", "jane_doe")
                .claim("email", "jane@example.com")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(900))
                .build();

        return jwtEncoder
                .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }
}
