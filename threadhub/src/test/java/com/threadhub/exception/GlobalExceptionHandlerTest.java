package com.threadhub.exception;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Unit tests for {@link GlobalExceptionHandler}.
 *
 * <p>These tests run standalone MockMvc against a throwaway probe controller, so the real
 * {@code @ExceptionHandler} dispatch and JSON serialisation are exercised without a database,
 * a Spring Boot application context, or a running web server.
 */
class GlobalExceptionHandlerTest {

    private static final String PROBE = "/probe/failure";
    private static final String VALIDATED = "/probe/validated";
    private static final String TYPE_MISMATCH = "/probe/type-mismatch";

    private final ObjectMapper objectMapper = new ObjectMapper();

    private ExceptionProbeController controller;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        controller = new ExceptionProbeController();
        mockMvc = MockMvcBuilders
                .standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    // ---------------------------------------------------------------------
    // Exception -> HTTP status mappings
    // ---------------------------------------------------------------------

    static Stream<Arguments> exceptionMappings() {
        return Stream.of(
                // 404 - not found
                Arguments.of(new UserNotFoundException("User not found with id: 42"),
                        HttpStatus.NOT_FOUND, "User not found with id: 42"),
                Arguments.of(new CommunityNotFoundException("Community not found with id: 7"),
                        HttpStatus.NOT_FOUND, "Community not found with id: 7"),

                // 401 - authentication
                Arguments.of(new InvalidCredentialsException("Invalid email or password"),
                        HttpStatus.UNAUTHORIZED, "Invalid email or password"),

                // 403 - authorization
                Arguments.of(new AccessDeniedException("Access is denied"),
                        HttpStatus.FORBIDDEN, "Access is denied"),

                // 409 - conflict
                Arguments.of(new DuplicateUsernameException("Username already exists"),
                        HttpStatus.CONFLICT, "Username already exists"),
                Arguments.of(new DuplicateEmailException("Email already registered"),
                        HttpStatus.CONFLICT, "Email already registered"),
                Arguments.of(new DuplicateCommunityNameException("Community name already exists"),
                        HttpStatus.CONFLICT, "Community name already exists"),
                Arguments.of(new AlreadyMemberException("User is already a member of this community"),
                        HttpStatus.CONFLICT, "User is already a member of this community"),

                // 400 - business rule violations
                Arguments.of(new NotMemberException("User is not a member of this community"),
                        HttpStatus.BAD_REQUEST, "User is not a member of this community"),
                Arguments.of(new OwnerCannotLeaveException("Community owner cannot leave the community"),
                        HttpStatus.BAD_REQUEST, "Community owner cannot leave the community")
        );
    }

    @ParameterizedTest(name = "[{index}] {0} -> {1}")
    @MethodSource("exceptionMappings")
    @DisplayName("Each custom exception maps to its HTTP status with a matching JSON payload")
    void mapsCustomExceptionToStatusAndPayload(RuntimeException exception,
                                               HttpStatus expectedStatus,
                                               String expectedMessage) throws Exception {
        controller.failWith(exception);

        mockMvc.perform(get(PROBE))
                .andExpect(status().is(expectedStatus.value()))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(expectedStatus.value()))
                .andExpect(jsonPath("$.error").value(expectedStatus.getReasonPhrase()))
                .andExpect(jsonPath("$.message").value(expectedMessage))
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    // ---------------------------------------------------------------------
    // Response payload details
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("A blank AccessDenied message falls back to the generic 'Access denied' text")
    void blankAccessDeniedMessageFallsBackToDefaultText() throws Exception {
        controller.failWith(new AccessDeniedException("   "));

        mockMvc.perform(get(PROBE))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value("Forbidden"))
                .andExpect(jsonPath("$.message").value("Access denied"));
    }

    @Test
    @DisplayName("Errors that are not validation failures carry no validation details")
    void nonValidationErrorsCarryNoDetails() throws Exception {
        controller.failWith(new CommunityNotFoundException("Community not found with id: 7"));

        MvcResult result = mockMvc.perform(get(PROBE))
                .andExpect(status().isNotFound())
                .andReturn();

        JsonNode details = objectMapper
                .readTree(result.getResponse().getContentAsString())
                .get("details");

        boolean absentOrEmpty = details == null || details.isNull()
                || (details.isArray() && details.isEmpty());
        assertThat(absentOrEmpty)
                .as("a non-validation error must not report validation details")
                .isTrue();
    }

    @Test
    @DisplayName("The same exception type always maps to the same status and error, regardless of message")
    void sameExceptionTypeIsMappedConsistently() throws Exception {
        controller.failWith(new CommunityNotFoundException("Community not found with id: 7"));
        JsonNode first = performAndParse(PROBE);

        controller.failWith(new CommunityNotFoundException("Community not found with id: 999"));
        JsonNode second = performAndParse(PROBE);

        assertThat(first.get("status").asInt()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(second.get("status").asInt()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(second.get("error").asText()).isEqualTo(first.get("error").asText());
        assertThat(second.get("message").asText()).isNotEqualTo(first.get("message").asText());

        controller.failWith(new CommunityNotFoundException("Community not found with id: 7"));
        JsonNode third = performAndParse(PROBE);
        assertThat(third.get("error").asText()).isEqualTo(first.get("error").asText());
        assertThat(third.get("message").asText()).isEqualTo(first.get("message").asText());
    }

    @Test
    @DisplayName("POST with an invalid body returns 400 with per-field validation details")
    void validationFailuresReturn400WithDetails() throws Exception {
        mockMvc.perform(post(VALIDATED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"email\":\"not-an-email\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.details").isArray())
                .andExpect(jsonPath("$.details.length()").value(2))
                .andExpect(jsonPath("$.details").value(
                        org.hamcrest.Matchers.containsInAnyOrder(
                                "must not be blank", "must be a well-formed email address")));
    }

    @Test
    @DisplayName("POST with malformed JSON returns 400 with a readable message")
    void malformedJsonReturns400() throws Exception {
        mockMvc.perform(post(VALIDATED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("Malformed JSON request body"));
    }

    @Test
    @DisplayName("A path variable of the wrong type returns 400 naming the parameter")
    void typeMismatchReturns400() throws Exception {
        mockMvc.perform(get(TYPE_MISMATCH + "/{id}", "not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("Invalid parameter type for: id"));
    }

    @Test
    @DisplayName("An unexpected exception returns 500 without leaking internal details")
    void unexpectedExceptionReturns500WithoutLeakingDetails() throws Exception {
        controller.failWith(new IllegalStateException("sensitive internal detail"));

        mockMvc.perform(get(PROBE))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.error").value("Internal Server Error"))
                .andExpect(jsonPath("$.message").value("An unexpected internal server error occurred"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("sensitive"))));
    }

    // ---------------------------------------------------------------------
    // Helpers and probe controller
    // ---------------------------------------------------------------------

    private JsonNode performAndParse(String url) throws Exception {
        MvcResult result = mockMvc.perform(get(url))
                .andExpect(status().is4xxClientError())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }


    /** A minimal controller whose only job is to raise the exception under test. */
    @RestController
    static class ExceptionProbeController {

        private Exception failure;

        void failWith(Exception failure) {
            this.failure = failure;
        }

        @GetMapping(PROBE)
        ResponseEntity<Void> failure() throws Exception {
            throw failure;
        }

        @PostMapping(VALIDATED)
        ResponseEntity<String> validated(@Valid @RequestBody ValidatedRequest request) {
            return ResponseEntity.ok("ok");
        }

        @GetMapping(TYPE_MISMATCH + "/{id}")
        ResponseEntity<String> typeMismatch(@PathVariable("id") Long id) {
            return ResponseEntity.ok(String.valueOf(id));
        }
    }

    /** Body used to trigger {@code MethodArgumentNotValidException} and unreadable-JSON handling. */
    static class ValidatedRequest {

        @NotBlank
        private String name;

        @Email
        private String email;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getEmail() {
            return email;
        }

        public void setEmail(String email) {
            this.email = email;
        }
    }
}
