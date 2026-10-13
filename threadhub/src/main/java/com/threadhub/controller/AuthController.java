package com.threadhub.controller;

import com.threadhub.dto.AuthResponse;
import com.threadhub.dto.LoginRequest;
import com.threadhub.dto.LogoutRequest;
import com.threadhub.dto.RefreshTokenRequest;
import com.threadhub.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        AuthResponse response = authService.login(request);
        return ResponseEntity.ok(response);
    }

    /** Public: the refresh token in the body is the credential, so no bearer token is required. */
    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return ResponseEntity.ok(authService.refresh(request));
    }

    /**
     * Requires a valid access token: that token identifies the session being ended and is itself
     * revoked. The body is optional — without a refresh token only the access token is blacklisted.
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@AuthenticationPrincipal Jwt jwt,
                                       @RequestBody(required = false) LogoutRequest request) {
        authService.logout(
                request == null ? null : request.getRefreshToken(),
                jwt.getId(),
                subjectAsUserId(jwt),
                jwt.getExpiresAt());
        return ResponseEntity.noContent().build();
    }

    private Long subjectAsUserId(Jwt jwt) {
        try {
            return Long.parseLong(jwt.getSubject());
        } catch (NumberFormatException | NullPointerException e) {
            return null;
        }
    }
}
