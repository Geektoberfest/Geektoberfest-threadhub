package com.threadhub.service;

import com.threadhub.dto.AuthResponse;
import com.threadhub.dto.LoginRequest;
import com.threadhub.dto.UserResponse;
import com.threadhub.event.AuditEvent;
import com.threadhub.exception.InvalidCredentialsException;
import com.threadhub.model.AuditAction;
import com.threadhub.model.User;
import com.threadhub.repository.UserRepository;
import com.threadhub.security.JwtService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final ApplicationEventPublisher eventPublisher;

    public AuthService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService,
                       ApplicationEventPublisher eventPublisher) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.eventPublisher = eventPublisher;
    }

    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new InvalidCredentialsException("Invalid email or password"));

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new InvalidCredentialsException("Invalid email or password");
        }

        String token = jwtService.generateToken(user);

        // Asynchronously publish login audit event
        eventPublisher.publishEvent(new AuditEvent(
                this,
                user.getId(),
                AuditAction.LOGIN,
                "USER",
                user.getId(),
                "User logged in successfully"
        ));

        return AuthResponse.builder()
                .accessToken(token)
                .tokenType("Bearer")
                .expiresIn(jwtService.getExpirationTimeMs())
                .user(UserResponse.fromEntity(user))
                .build();
    }
}