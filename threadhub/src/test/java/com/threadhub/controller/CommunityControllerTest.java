package com.threadhub.controller;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.threadhub.config.SecurityConfig;
import com.threadhub.dto.*;
import com.threadhub.exception.AlreadyMemberException;
import com.threadhub.exception.CommunityNotFoundException;
import com.threadhub.exception.GlobalExceptionHandler;
import com.threadhub.model.CommunityMemberRole;
import com.threadhub.model.User;
import com.threadhub.security.JwtService;
import com.threadhub.service.CommunityMemberService;
import com.threadhub.service.CommunityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(CommunityController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
@TestPropertySource(properties = {
        "jwt.secret=404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970"
})
class CommunityControllerTest {

    private static final String TEST_SECRET = "404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CommunityService communityService;

    @MockitoBean
    private CommunityMemberService communityMemberService;

    private String validJwtToken;
    private CommunityResponse sampleCommunityResponse;
    private CommunityMemberResponse sampleMemberResponse;

    @BeforeEach
    void setUp() {
        SecretKey secretKey = new SecretKeySpec(TEST_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        JWKSource<SecurityContext> jwks = new ImmutableSecret<>(secretKey);
        JwtEncoder jwtEncoder = new NimbusJwtEncoder(jwks);
        JwtService jwtService = new JwtService(jwtEncoder, 86400000L);

        User user = User.builder().id(1L).username("testuser").email("test@example.com").build();
        validJwtToken = jwtService.generateToken(user);

        UserResponse ownerResponse = UserResponse.builder()
                .id(1L)
                .username("testuser")
                .email("test@example.com")
                .build();

        sampleCommunityResponse = CommunityResponse.builder()
                .id(10L)
                .name("spring")
                .description("Spring boot discussion")
                .owner(ownerResponse)
                .memberCount(1L)
                .createdAt(LocalDateTime.now())
                .build();

        sampleMemberResponse = CommunityMemberResponse.builder()
                .userId(1L)
                .username("testuser")
                .role(CommunityMemberRole.MEMBER)
                .joinedAt(LocalDateTime.now())
                .build();
    }

    @Test
    @DisplayName("POST /api/communities - Should reject unauthenticated user with 401 Unauthorized")
    void shouldRejectUnauthenticatedCommunityCreation() throws Exception {
        String jsonRequest = """
                {
                    "name": "spring",
                    "description": "Spring framework"
                }
                """;

        mockMvc.perform(post("/api/communities")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonRequest))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /api/communities - Should allow authenticated user to create community (201 Created)")
    void shouldAllowAuthenticatedUserToCreateCommunity() throws Exception {
        when(communityService.createCommunity(any(CommunityCreateRequest.class))).thenReturn(sampleCommunityResponse);

        String jsonRequest = """
                {
                    "name": "spring",
                    "description": "Spring framework"
                }
                """;

        mockMvc.perform(post("/api/communities")
                        .header("Authorization", "Bearer " + validJwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonRequest))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(10))
                .andExpect(jsonPath("$.name").value("spring"))
                .andExpect(jsonPath("$.owner.username").value("testuser"));
    }

    @Test
    @DisplayName("POST /api/communities - Should return 400 Bad Request when validation fails")
    void shouldReturnBadRequestWhenValidationFails() throws Exception {
        String invalidJsonRequest = """
                {
                    "name": "ab",
                    "description": "Too short name"
                }
                """;

        mockMvc.perform(post("/api/communities")
                        .header("Authorization", "Bearer " + validJwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidJsonRequest))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Validation failed"));
    }

    @Test
    @DisplayName("POST /api/communities - Should return 400 Bad Request when JSON request body is malformed")
    void shouldReturnBadRequestWhenJsonIsMalformed() throws Exception {
        String malformedJson = "{ \"name\": \"spring\", \"description\": }";

        mockMvc.perform(post("/api/communities")
                        .header("Authorization", "Bearer " + validJwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(malformedJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Malformed JSON request body"));
    }

    @Test
    @DisplayName("GET /api/communities - Should permit public access to list communities")
    void shouldAllowPublicCommunityListing() throws Exception {
        when(communityService.getAllCommunities()).thenReturn(List.of(sampleCommunityResponse));

        mockMvc.perform(get("/api/communities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("spring"));
    }

    @Test
    @DisplayName("GET /api/communities/{id} - Should return 404 Not Found when community does not exist")
    void shouldReturnNotFoundWhenCommunityMissing() throws Exception {
        when(communityService.getCommunityById(99L)).thenThrow(new CommunityNotFoundException("Community not found with id: 99"));

        mockMvc.perform(get("/api/communities/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @DisplayName("GET /api/communities/{id} - Should return 400 Bad Request when path parameter type is invalid")
    void shouldReturnBadRequestOnTypeMismatch() throws Exception {
        mockMvc.perform(get("/api/communities/invalid-id")
                        .header("Authorization", "Bearer " + validJwtToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Invalid parameter type for: id"));
    }

    @Test
    @DisplayName("POST /api/communities/{id}/join - Should reject unauthenticated user with 401 Unauthorized")
    void shouldRejectUnauthenticatedJoin() throws Exception {
        mockMvc.perform(post("/api/communities/10/join"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /api/communities/{id}/join - Should allow authenticated user to join community")
    void shouldAllowAuthenticatedUserToJoin() throws Exception {
        when(communityMemberService.joinCommunity(10L)).thenReturn(sampleMemberResponse);

        mockMvc.perform(post("/api/communities/10/join")
                        .header("Authorization", "Bearer " + validJwtToken))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").value(1))
                .andExpect(jsonPath("$.role").value("MEMBER"));
    }

    @Test
    @DisplayName("POST /api/communities/{id}/join - Should return 409 Conflict when user is already a member")
    void shouldReturnConflictWhenAlreadyMember() throws Exception {
        when(communityMemberService.joinCommunity(10L)).thenThrow(new AlreadyMemberException("User is already a member"));

        mockMvc.perform(post("/api/communities/10/join")
                        .header("Authorization", "Bearer " + validJwtToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    @DisplayName("PUT /api/communities/{id} - Should update the community for its owner (200 OK)")
    void shouldAllowOwnerToUpdateCommunity() throws Exception {
        CommunityResponse updatedResponse = CommunityResponse.builder()
                .id(10L)
                .name("spring")
                .description("Updated description")
                .owner(sampleCommunityResponse.getOwner())
                .memberCount(1L)
                .createdAt(sampleCommunityResponse.getCreatedAt())
                .updatedAt(LocalDateTime.now())
                .build();
        when(communityService.updateCommunity(eq(10L), any(CommunityUpdateRequest.class))).thenReturn(updatedResponse);

        String jsonRequest = """
                {
                    "description": "Updated description"
                }
                """;

        mockMvc.perform(put("/api/communities/10")
                        .header("Authorization", "Bearer " + validJwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonRequest))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(10))
                .andExpect(jsonPath("$.name").value("spring"))
                .andExpect(jsonPath("$.description").value("Updated description"))
                .andExpect(jsonPath("$.owner.username").value("testuser"))
                .andExpect(jsonPath("$.memberCount").value(1))
                .andExpect(jsonPath("$.updatedAt").exists());
    }

    @Test
    @DisplayName("PUT /api/communities/{id} - Should ignore a name supplied in the request body")
    void shouldIgnoreNameInUpdateRequest() throws Exception {
        when(communityService.updateCommunity(eq(10L), any(CommunityUpdateRequest.class))).thenReturn(sampleCommunityResponse);

        String jsonRequest = """
                {
                    "name": "hijacked-name",
                    "description": "Renamed attempt"
                }
                """;

        mockMvc.perform(put("/api/communities/10")
                        .header("Authorization", "Bearer " + validJwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonRequest))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("spring"))
                .andExpect(jsonPath("$.description").value("Spring boot discussion"));

        ArgumentCaptor<CommunityUpdateRequest> requestCaptor = ArgumentCaptor.forClass(CommunityUpdateRequest.class);
        verify(communityService).updateCommunity(eq(10L), requestCaptor.capture());
        assertThat(requestCaptor.getValue().getDescription()).isEqualTo("Renamed attempt");
    }

    @Test
    @DisplayName("PUT /api/communities/{id} - Should reject unauthenticated user with 401 Unauthorized")
    void shouldRejectUnauthenticatedCommunityUpdate() throws Exception {
        mockMvc.perform(put("/api/communities/10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"anything\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("PUT /api/communities/{id} - Should return 403 Forbidden for a non-owner")
    void shouldReturnForbiddenWhenNonOwnerUpdates() throws Exception {
        when(communityService.updateCommunity(eq(10L), any(CommunityUpdateRequest.class)))
                .thenThrow(new AccessDeniedException("Only the community owner can update this community"));

        mockMvc.perform(put("/api/communities/10")
                        .header("Authorization", "Bearer " + validJwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"anything\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.message").value("Only the community owner can update this community"));
    }

    @Test
    @DisplayName("PUT /api/communities/{id} - Should return 404 Not Found when the community does not exist")
    void shouldReturnNotFoundWhenUpdatingMissingCommunity() throws Exception {
        when(communityService.updateCommunity(eq(99L), any(CommunityUpdateRequest.class)))
                .thenThrow(new CommunityNotFoundException("Community not found with id: 99"));

        mockMvc.perform(put("/api/communities/99")
                        .header("Authorization", "Bearer " + validJwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"anything\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @DisplayName("PUT /api/communities/{id} - Should return 400 Bad Request when the description is too long")
    void shouldReturnBadRequestWhenUpdatePayloadIsInvalid() throws Exception {
        String tooLongDescription = "x".repeat(501);

        mockMvc.perform(put("/api/communities/10")
                        .header("Authorization", "Bearer " + validJwtToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"" + tooLongDescription + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.details[0]").value("Description must not exceed 500 characters"));
    }

    @Test
    @DisplayName("DELETE /api/communities/{id} - Should delete the community for its owner (204 No Content)")
    void shouldAllowOwnerToDeleteCommunity() throws Exception {
        mockMvc.perform(delete("/api/communities/10")
                        .header("Authorization", "Bearer " + validJwtToken))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        verify(communityService).deleteCommunity(10L);
    }

    @Test
    @DisplayName("DELETE /api/communities/{id} - Should reject unauthenticated user with 401 Unauthorized")
    void shouldRejectUnauthenticatedCommunityDeletion() throws Exception {
        mockMvc.perform(delete("/api/communities/10"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("DELETE /api/communities/{id} - Should return 403 Forbidden for a non-owner")
    void shouldReturnForbiddenWhenNonOwnerDeletes() throws Exception {
        doThrow(new AccessDeniedException("Only the community owner can delete this community"))
                .when(communityService).deleteCommunity(anyLong());

        mockMvc.perform(delete("/api/communities/10")
                        .header("Authorization", "Bearer " + validJwtToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.message").value("Only the community owner can delete this community"));
    }

    @Test
    @DisplayName("DELETE /api/communities/{id} - Should return 404 Not Found when the community does not exist")
    void shouldReturnNotFoundWhenDeletingMissingCommunity() throws Exception {
        doThrow(new CommunityNotFoundException("Community not found with id: 99"))
                .when(communityService).deleteCommunity(99L);

        mockMvc.perform(delete("/api/communities/99")
                        .header("Authorization", "Bearer " + validJwtToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @DisplayName("GET /api/communities/{id}/members - Should permit public access to member listing")
    void shouldAllowPublicMemberListing() throws Exception {
        when(communityMemberService.getCommunityMembers(10L)).thenReturn(List.of(sampleMemberResponse));

        mockMvc.perform(get("/api/communities/10/members"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].username").value("testuser"))
                .andExpect(jsonPath("$[0].role").value("MEMBER"));
    }
}
