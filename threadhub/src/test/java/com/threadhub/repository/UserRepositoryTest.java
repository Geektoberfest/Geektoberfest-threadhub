package com.threadhub.repository;

import com.threadhub.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Transactional
class UserRepositoryTest {

    @Autowired
    private UserRepository userRepository;

    private User sampleUser;

    @BeforeEach
    void setUp() {
        sampleUser = User.builder()
                .username("testuser")
                .email("test@example.com")
                .password("password123")
                .build();
    }

    @Test
    @DisplayName("Should save user and auto-generate ID and timestamps")
    void shouldSaveUser() {
        User savedUser = userRepository.save(sampleUser);

        assertThat(savedUser.getId()).isNotNull();
        assertThat(savedUser.getUsername()).isEqualTo("testuser");
        assertThat(savedUser.getEmail()).isEqualTo("test@example.com");
        assertThat(savedUser.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("Should find user by username")
    void shouldFindByUsername() {
        userRepository.save(sampleUser);

        Optional<User> found = userRepository.findByUsername("testuser");

        assertThat(found).isPresent();
        assertThat(found.get().getEmail()).isEqualTo("test@example.com");
    }

    @Test
    @DisplayName("Should find user by email")
    void shouldFindByEmail() {
        userRepository.save(sampleUser);

        Optional<User> found = userRepository.findByEmail("test@example.com");

        assertThat(found).isPresent();
        assertThat(found.get().getUsername()).isEqualTo("testuser");
    }

    @Test
    @DisplayName("Should return true when username exists")
    void shouldCheckExistsByUsername() {
        userRepository.save(sampleUser);

        assertThat(userRepository.existsByUsername("testuser")).isTrue();
        assertThat(userRepository.existsByUsername("nonexistent")).isFalse();
    }

    @Test
    @DisplayName("Should return true when email exists")
    void shouldCheckExistsByEmail() {
        userRepository.save(sampleUser);

        assertThat(userRepository.existsByEmail("test@example.com")).isTrue();
        assertThat(userRepository.existsByEmail("other@example.com")).isFalse();
    }
}
