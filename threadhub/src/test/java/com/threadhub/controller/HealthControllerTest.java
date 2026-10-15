package com.threadhub.controller;

import com.threadhub.config.SecurityConfig;
import com.threadhub.dto.HealthResponse;
import com.threadhub.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(HealthController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
@TestPropertySource(properties = {
        "jwt.secret=404E635266556A586E3272357538782F413F44284B6250645367566B5970"
})
class HealthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("GET /api/health - Should return HTTP 200 without authentication")
    void shouldReturn200WithoutAuthentication() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /api/health - Should return status UP")
    void shouldReturnStatusUp() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @DisplayName("GET /api/health - Should return a valid UTC timestamp")
    void shouldReturnValidUtcTimestamp() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(result -> {
                    String timestamp = result.getResponse().getContentAsString()
                            .split("\"timestamp\":\"")[1]
                            .split("\"")[0];
                    Instant parsed = Instant.parse(timestamp);
                    if (!timestamp.endsWith("Z")) {
                        throw new AssertionError("Timestamp is not UTC (no Z suffix): " + timestamp);
                    }
                    if (Instant.now().isAfter(parsed.plus(5, ChronoUnit.SECONDS))) {
                        throw new AssertionError("Timestamp is too old: " + timestamp);
                    }
                });
    }

    @Test
    @DisplayName("GET /api/health - Should be publicly accessible without JWT token")
    void shouldBePubliclyAccessible() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @DisplayName("POST /api/communities - Existing protected endpoint remains protected")
    void existingProtectedEndpointRemainsProtected() throws Exception {
        mockMvc.perform(post("/api/communities")
                        .contentType("application/json")
                        .content("{\"name\":\"test\"}"))
                .andExpect(status().isUnauthorized());
    }
}
