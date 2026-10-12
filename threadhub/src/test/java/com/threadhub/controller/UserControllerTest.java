package com.threadhub.controller;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.threadhub.config.RateLimitFilter;
import com.threadhub.config.SecurityConfig;
import com.threadhub.dto.UserCreateRequest;
import com.threadhub.dto.UserResponse;
import com.threadhub.exception.DuplicateEmailException;
import com.threadhub.exception.DuplicateUsernameException;
import com.threadhub.exception.GlobalExceptionHandler;
import com.threadhub.exception.UserNotFoundException;
import com.threadhub.model.User;
import com.threadhub.security.JwtService;
import com.threadhub.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(UserController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class, RateLimitFilter.class})
@TestPropertySource(properties = {
        "jwt.secret=404E635266556A586E3272357538782F413F44284B6250645367566B5970",
        "rate-limit.login-requests-per-window=5",
        "rate-limit.registration-requests-per-window=5",
        "rate-limit.window-duration=PT1S"
})
class UserControllerTest {

    private static final String TEST_SECRET = "404E635266556A586E3272357538782F413F44284B6250645367566B5970";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserService userService;

    private UserResponse sampleResponse;
    private String validJwtToken;
    private String expiredJwtToken;

    @BeforeEach
    void setUp() {
        sampleResponse = UserResponse.builder()
                .id(1L)
                .username("jane_doe")
                .email("jane@example.com")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        SecretKey secretKey = new SecretKeySpec(TEST_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        JWKSource<SecurityContext> jwks = new ImmutableSecret<>(secretKey);
        JwtEncoder jwtEncoder = new NimbusJwtEncoder(jwks);
        JwtService jwtService = new JwtService(jwtEncoder, 86400000L);

        User user = User.builder()
                .id(1L)
                .username("jane_doe")
                .email("jane@example.com")
                .build();

        validJwtToken = jwtService.generateToken(user);

        Instant pastIssuedAt = Instant.now().minusSeconds(600);
        Instant pastExpiresAt = Instant.now().minusSeconds(300);
        JwtClaimsSet expiredClaims = JwtClaimsSet.builder()
                .subject("1")
                .claim("username", "jane_doe")
                .claim("email", "jane@example.com")
                .issuedAt(pastIssuedAt)
                .expiresAt(pastExpiresAt)
                .build();
        JwsHeader jwsHeader = JwsHeader.with(MacAlgorithm.HS256).build();
        expiredJwtToken = jwtEncoder.encode(JwtEncoderParameters.from(jwsHeader, expiredClaims)).getTokenValue();
    }

    private String registrationJson(String username, String email) {
        return """
                {
                    "username": "%s",
                    "email": "%s",
                    "password": "password123"
                }
                """.formatted(username, email);
    }

    @Test
    @DisplayName("POST /api/users - Should permit public registration without token and return 201 Created")
    void shouldCreateUserPublicly() throws Exception {
        when(userService.createUser(any(UserCreateRequest.class))).thenReturn(sampleResponse);

        mockMvc.perform(post("/api/users")
                        .header("X-Forwarded-For", "20.0.0.1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registrationJson("jane_doe", "jane@example.com")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.username").value("jane_doe"))
                .andExpect(jsonPath("$.email").value("jane@example.com"))
                .andExpect(jsonPath("$.password").doesNotExist());
    }

    @Test
    @DisplayName("POST /api/users - Should return 400 Bad Request when validation fails")
    void shouldReturnBadRequestWhenValidationFails() throws Exception {
        mockMvc.perform(post("/api/users")
                        .header("X-Forwarded-For", "20.0.0.2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "username": "ab",
                                    "email": "invalid-email",
                                    "password": ""
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.details").isArray());
    }

    @Test
    @DisplayName("POST /api/users - Should return 400 Bad Request when JSON request body is malformed")
    void shouldReturnBadRequestWhenJsonIsMalformed() throws Exception {
        mockMvc.perform(post("/api/users")
                        .header("X-Forwarded-For", "20.0.0.3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"username\": \"jane\", \"email\": }"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Malformed JSON request body"));
    }

    @Test
    @DisplayName("POST /api/users - Should return 409 Conflict when username is duplicate")
    void shouldReturnConflictOnDuplicateUsername() throws Exception {
        String jsonRequest = registrationJson("jane_doe", "jane@example.com");

        when(userService.createUser(any(UserCreateRequest.class)))
                .thenThrow(new DuplicateUsernameException("Username already taken: jane_doe"));

        mockMvc.perform(post("/api/users")
                        .header("X-Forwarded-For", "20.0.0.4")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonRequest))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value("Username already taken: jane_doe"));
    }

    @Test
    @DisplayName("POST /api/users - Should return 409 Conflict when email is duplicate")
    void shouldReturnConflictOnDuplicateEmail() throws Exception {
        String jsonRequest = registrationJson("jane_doe", "jane@example.com");

        when(userService.createUser(any(UserCreateRequest.class)))
                .thenThrow(new DuplicateEmailException("Email already registered: jane@example.com"));

        mockMvc.perform(post("/api/users")
                        .header("X-Forwarded-For", "20.0.0.5")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonRequest))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value("Email already registered: jane@example.com"));
    }

    @Test
    @DisplayName("POST /api/users - Should return 429 Too Many Requests when registration rate limit is exceeded")
    void shouldReturn429WhenRegistrationRateLimitExceeded() throws Exception {
        when(userService.createUser(any(UserCreateRequest.class))).thenReturn(sampleResponse);

        String clientIp = "20.10.10.1";

        // Exhaust the rate limit of 5 requests for this IP
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/users")
                            .header("X-Forwarded-For", clientIp)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(registrationJson("newuser" + i, "new" + i + "@example.com")))
                    .andExpect(status().isCreated());
        }

        // The 6th request from the same IP should be rate limited
        mockMvc.perform(post("/api/users")
                        .header("X-Forwarded-For", clientIp)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registrationJson("newuser6", "new6@example.com")))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.error").value("Too Many Requests"))
                .andExpect(jsonPath("$.message").value("Rate limit exceeded. Please try again later."));
    }

    @Test
    @DisplayName("POST /api/users - Rate limit should be scoped to client IP")
    void registrationRateLimitShouldBeScopedToClientIp() throws Exception {
        when(userService.createUser(any(UserCreateRequest.class))).thenReturn(sampleResponse);

        String exhaustedIp = "20.20.20.1";
        String freshIp = "20.20.20.2";

        // Exhaust rate limit for one IP
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/users")
                            .header("X-Forwarded-For", exhaustedIp)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(registrationJson("user" + i, "user" + i + "@example.com")))
                    .andExpect(status().isCreated());
        }

        // Different IP should still be allowed
        mockMvc.perform(post("/api/users")
                        .header("X-Forwarded-For", freshIp)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registrationJson("freshuser", "fresh@example.com")))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("POST /api/users - OPTIONS preflight should not be rate limited")
    void optionsPreflightShouldNotBeRateLimited() throws Exception {
        when(userService.createUser(any(UserCreateRequest.class))).thenReturn(sampleResponse);

        // Send a bad POST from one IP
        mockMvc.perform(post("/api/users")
                        .header("X-Forwarded-For", "20.30.30.1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        // OPTIONS requests from different IPs should not consume tokens
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/users")
                            .header("X-Forwarded-For", "20.30.30." + (i + 2))
                            .header("Origin", "http://localhost:5173")
                            .header("Access-Control-Request-Method", "POST")
                            .header("Access-Control-Request-Headers", "Content-Type"))
                    .andExpect(status().isBadRequest());
        }

        // A real POST from a fresh, unused IP should still work
        mockMvc.perform(post("/api/users")
                        .header("X-Forwarded-For", "20.30.30.10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registrationJson("freshuser", "fresh@example.com")))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("GET /api/users/{id} - Should reject unauthenticated requests with 401 Unauthorized")
    void shouldRejectUnauthenticatedRequest() throws Exception {
        mockMvc.perform(get("/api/users/1"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /api/users/{id} - Should reject requests with invalid Bearer token with 401 Unauthorized")
    void shouldRejectInvalidBearerToken() throws Exception {
        mockMvc.perform(get("/api/users/1")
                        .header("Authorization", "Bearer invalid.jwt.token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /api/users/{id} - Should reject expired Bearer token with 401 Unauthorized")
    void shouldRejectExpiredBearerToken() throws Exception {
        mockMvc.perform(get("/api/users/1")
                        .header("Authorization", "Bearer " + expiredJwtToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /api/users/{id} - Should accept requests with valid Bearer token and return 200 OK")
    void shouldAcceptValidBearerToken() throws Exception {
        when(userService.getUserById(1L)).thenReturn(sampleResponse);

        mockMvc.perform(get("/api/users/1")
                        .header("Authorization", "Bearer " + validJwtToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.username").value("jane_doe"))
                .andExpect(jsonPath("$.email").value("jane@example.com"));
    }

    @Test
    @DisplayName("GET /api/users/{id} - Should return 400 Bad Request when path parameter type is invalid")
    void shouldReturnBadRequestOnTypeMismatch() throws Exception {
        mockMvc.perform(get("/api/users/invalid-id")
                        .header("Authorization", "Bearer " + validJwtToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Invalid parameter type for: id"));
    }

    @Test
    @DisplayName("GET /api/users/{id} - Should return 404 Not Found when authenticated user does not exist")
    void shouldReturnNotFoundWhenUserDoesNotExist() throws Exception {
        when(userService.getUserById(99L)).thenThrow(new UserNotFoundException("User not found with id: 99"));

        mockMvc.perform(get("/api/users/99")
                        .header("Authorization", "Bearer " + validJwtToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("User not found with id: 99"));
    }
}
