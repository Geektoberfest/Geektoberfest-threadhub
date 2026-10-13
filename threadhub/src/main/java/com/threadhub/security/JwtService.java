package com.threadhub.security;

import com.threadhub.model.User;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/**
 * Mints short-lived access tokens. The lifetime is configured independently of the refresh-token
 * lifetime, which {@code RefreshTokenService} owns.
 */
@Service
public class JwtService {

    private final JwtEncoder jwtEncoder;
    private final long accessTokenExpirationMs;

    public JwtService(JwtEncoder jwtEncoder,
                      @Value("${jwt.access-token-expiration:900000}") long accessTokenExpirationMs) {
        this.jwtEncoder = jwtEncoder;
        this.accessTokenExpirationMs = accessTokenExpirationMs;
    }

    public String generateToken(User user) {
        Instant now = Instant.now();
        Instant expiresAt = now.plusMillis(accessTokenExpirationMs);

        JwtClaimsSet claims = JwtClaimsSet.builder()
                // A stable identifier is required so logout can revoke this exact token.
                .id(UUID.randomUUID().toString())
                .subject(user.getId().toString())
                .claim("username", user.getUsername())
                .claim("email", user.getEmail())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .build();

        JwsHeader jwsHeader = JwsHeader.with(MacAlgorithm.HS256).build();

        return jwtEncoder.encode(JwtEncoderParameters.from(jwsHeader, claims)).getTokenValue();
    }

    public long getExpirationTimeMs() {
        return accessTokenExpirationMs;
    }
}
