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

@Service
public class JwtService {

    private final JwtEncoder jwtEncoder;
    private final long jwtExpirationMs;
    // 15 minutes in milliseconds = 15 * 60 * 1000 = 900,000 ms
    private long jwtExpiration = 900000;

    public JwtService(JwtEncoder jwtEncoder, @Value("${jwt.expiration:86400000}") long jwtExpirationMs) {
        this.jwtEncoder = jwtEncoder;
        this.jwtExpirationMs = jwtExpirationMs;
    }

    public String generateToken(User user) {
        Instant now = Instant.now();
        Instant expiresAt = now.plusMillis(jwtExpirationMs);

        JwtClaimsSet claims = JwtClaimsSet.builder()
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
        return jwtExpirationMs;
    }
}
