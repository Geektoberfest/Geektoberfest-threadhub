package com.threadhub.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.threadhub.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private static final String TEST_SECRET = "404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970";
    private JwtService jwtService;
    private JwtDecoder jwtDecoder;
    private JwtEncoder jwtEncoder;
    private User sampleUser;

    @BeforeEach
    void setUp() {
        SecretKey secretKey = new SecretKeySpec(TEST_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        JWKSource<SecurityContext> jwks = new ImmutableSecret<>(secretKey);
        jwtEncoder = new NimbusJwtEncoder(jwks);

        jwtDecoder = NimbusJwtDecoder.withSecretKey(secretKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();

        jwtService = new JwtService(jwtEncoder, 86400000L);

        sampleUser = User.builder()
                .id(42L)
                .username("alice")
                .email("alice@example.com")
                .password("encoded_pass")
                .build();
    }

    @Test
    @DisplayName("Should generate valid JWT token with correct claims")
    void shouldGenerateValidToken() {
        String token = jwtService.generateToken(sampleUser);

        assertThat(token).isNotBlank();

        Jwt decodedJwt = jwtDecoder.decode(token);

        assertThat(decodedJwt.getSubject()).isEqualTo("42");
        assertThat(decodedJwt.getClaimAsString("username")).isEqualTo("alice");
        assertThat(decodedJwt.getClaimAsString("email")).isEqualTo("alice@example.com");
        assertThat(decodedJwt.getIssuedAt()).isNotNull();
        assertThat(decodedJwt.getExpiresAt()).isNotNull();
    }

    @Test
    @DisplayName("Should fail validation for expired JWT token")
    void shouldFailForExpiredToken() {
        Instant pastIssuedAt = Instant.now().minusSeconds(3600);
        Instant pastExpiresAt = Instant.now().minusSeconds(1800);

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(sampleUser.getId().toString())
                .claim("username", sampleUser.getUsername())
                .claim("email", sampleUser.getEmail())
                .issuedAt(pastIssuedAt)
                .expiresAt(pastExpiresAt)
                .build();

        JwsHeader jwsHeader = JwsHeader.with(MacAlgorithm.HS256).build();

        String expiredToken = jwtEncoder.encode(JwtEncoderParameters.from(jwsHeader, claims)).getTokenValue();

        assertThatThrownBy(() -> jwtDecoder.decode(expiredToken))
                .isInstanceOf(BadJwtException.class);
    }

    @Test
    @DisplayName("Should embed a unique jti so an individual access token can be revoked")
    void shouldEmbedUniqueTokenIdentifiers() {
        String firstToken = jwtService.generateToken(sampleUser);
        String secondToken = jwtService.generateToken(sampleUser);

        String firstJti = jwtDecoder.decode(firstToken).getId();
        String secondJti = jwtDecoder.decode(secondToken).getId();

        assertThat(firstJti).isNotBlank();
        assertThat(secondJti).isNotBlank();
        assertThat(firstJti).isNotEqualTo(secondJti);
    }

    @Test
    @DisplayName("Should honour the configured access-token lifetime")
    void shouldHonourConfiguredAccessTokenLifetime() {
        long fifteenMinutes = 900_000L;
        JwtService shortLivedService = new JwtService(jwtEncoder, fifteenMinutes);

        Jwt decoded = jwtDecoder.decode(shortLivedService.generateToken(sampleUser));

        assertThat(Duration.between(decoded.getIssuedAt(), decoded.getExpiresAt()).toMillis())
                .isEqualTo(fifteenMinutes);
    }
}
