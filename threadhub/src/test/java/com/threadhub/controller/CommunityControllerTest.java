package com.threadhub.controller;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.threadhub.config.SecurityConfig;
import com.threadhub.dto.*;
import com.threadhub.exception.AlreadyMemberException;
import com.threadhub.exception.CommunityNotFoundException;
import com.threadhub.exception.GlobalExceptionHandler;
import com.threadhub.exception.ModeratorRoleChangeException;
import com.threadhub.exception.NotMemberException;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(CommunityController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
@TestPropertySource(properties = {
        "jwt.secret=404E635266556A586E3272357538782F413F44284B6250645367566B5970"
})
class CommunityControllerTest {

    private static final String TEST_SECRET = "404E635266556A586E3272357538782F413F44284B6250645367566B5970";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CommunityService communityService;

    @MockitoBean
    private CommunityMemberService communityMemberService;

    private String ownerToken;
    private String moderatorToken;
    private String memberToken;
    private CommunityMemberResponse sampleMemberResponse;
    private CommunityResponse sampleCommunityResponse;

    @BeforeEach
    void setUp() {
        SecretKey secretKey = new SecretKeySpec(TEST_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        JWKSource<SecurityContext> jwks = new ImmutableSecret<>(secretKey);
        JwtEncoder jwtEncoder = new NimbusJwtEncoder(jwks);
        JwtService jwtService = new JwtService(jwtEncoder, 86400000L);

        User owner = User.builder().id(1L).username("owner").email("owner@example.com").build();
        User moderator = User.builder().id(2L).username("moderator").email("mod@example.com").build();
        User member = User.builder().id(3L).username("member").email("member@example.com").build();

        ownerToken = jwtService.generateToken(owner);
        moderatorToken = jwtService.generateToken(moderator);
        memberToken = jwtService.generateToken(member);

        sampleCommunityResponse = CommunityResponse.builder()
                .id(10L)
                .name("spring")
                .description("Spring boot discussion")
                .owner(UserResponse.builder().id(1L).username("owner").email("owner@example.com").build())
                .memberCount(3L)
                .createdAt(LocalDateTime.now())
                .build();

        sampleMemberResponse = CommunityMemberResponse.builder()
                .userId(3L)
                .username("member")
                .role(CommunityMemberRole.MEMBER)
                .joinedAt(LocalDateTime.now())
                .build();
    }

    // --- Existing endpoint regression tests ---

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
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonRequest))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(10))
                .andExpect(jsonPath("$.name").value("spring"))
                .andExpect(jsonPath("$.owner.username").value("owner"));
    }

    @Test
    @DisplayName("GET /api/communities - Should permit public access to list communities")
    void shouldAllowPublicCommunityListing() throws Exception {
        when(communityService.getAllCommunities()).thenReturn(List.of(sampleCommunityResponse));

        mockMvc.perform(get("/api/communities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("spring"));
    }

    // --- Role change endpoint tests ---

    @Test
    @DisplayName("PUT /api/communities/{id}/members/{userId}/role - Owner promotes member to moderator (200 OK)")
    void ownerPromotesMemberToModerator() throws Exception {
        CommunityMemberResponse moderatorResponse = CommunityMemberResponse.builder()
                .userId(3L)
                .username("member")
                .role(CommunityMemberRole.MODERATOR)
                .joinedAt(LocalDateTime.now())
                .build();

        when(communityMemberService.updateMemberRole(10L, 3L, CommunityMemberRole.MODERATOR))
                .thenReturn(moderatorResponse);

        String jsonRequest = """
                {
                    "role": "MODERATOR"
                }
                """;

        mockMvc.perform(put("/api/communities/10/members/3/role")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonRequest))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(3))
                .andExpect(jsonPath("$.username").value("member"))
                .andExpect(jsonPath("$.role").value("MODERATOR"));
    }

    @Test
    @DisplayName("PUT /api/communities/{id}/members/{userId}/role - Owner demotes moderator to member (200 OK)")
    void ownerDemotesModeratorToMember() throws Exception {
        CommunityMemberResponse memberResponse = CommunityMemberResponse.builder()
                .userId(2L)
                .username("moderator")
                .role(CommunityMemberRole.MEMBER)
                .joinedAt(LocalDateTime.now())
                .build();

        when(communityMemberService.updateMemberRole(10L, 2L, CommunityMemberRole.MEMBER))
                .thenReturn(memberResponse);

        String jsonRequest = """
                {
                    "role": "MEMBER"
                }
                """;

        mockMvc.perform(put("/api/communities/10/members/2/role")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonRequest))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(2))
                .andExpect(jsonPath("$.role").value("MEMBER"));
    }

    @Test
    @DisplayName("PUT /api/communities/{id}/members/{userId}/role - Non-owner cannot promote a member (403 Forbidden)")
    void nonOwnerCannotPromoteMember() throws Exception {
        when(communityMemberService.updateMemberRole(10L, 3L, CommunityMemberRole.MODERATOR))
                .thenThrow(new ModeratorRoleChangeException("Only the community owner can change member roles"));

        String jsonRequest = """
                {
                    "role": "MODERATOR"
                }
                """;

        mockMvc.perform(put("/api/communities/10/members/3/role")
                        .header("Authorization", "Bearer " + moderatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonRequest))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.message").value("Only the community owner can change member roles"));
    }

    @Test
    @DisplayName("PUT /api/communities/{id}/members/{userId}/role - Moderator cannot modify another member's role (403 Forbidden)")
    void moderatorCannotModifyMemberRole() throws Exception {
        when(communityMemberService.updateMemberRole(10L, 3L, CommunityMemberRole.MODERATOR))
                .thenThrow(new ModeratorRoleChangeException("Only the community owner can change member roles"));

        String jsonRequest = """
                {
                    "role": "MODERATOR"
                }
                """;

        mockMvc.perform(put("/api/communities/10/members/3/role")
                        .header("Authorization", "Bearer " + moderatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonRequest))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.message").value("Only the community owner can change member roles"));
    }

    // --- Issue #12: community update and delete tests ---

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
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonRequest))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(10))
                .andExpect(jsonPath("$.name").value("spring"))
                .andExpect(jsonPath("$.description").value("Updated description"))
                .andExpect(jsonPath("$.owner.username").value("owner"))
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
                        .header("Authorization", "Bearer " + ownerToken)
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
                        .header("Authorization", "Bearer " + moderatorToken)
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
                        .header("Authorization", "Bearer " + ownerToken)
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
                        .header("Authorization", "Bearer " + ownerToken)
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
                        .header("Authorization", "Bearer " + ownerToken))
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
                        .header("Authorization", "Bearer " + moderatorToken))
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
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @DisplayName("GET /api/communities/{id}/members - Should permit public access to member listing")
    void shouldAllowPublicMemberListing() throws Exception {
        when(communityMemberService.getCommunityMembers(10L)).thenReturn(List.of(sampleMemberResponse));

        mockMvc.perform(get("/api/communities/10/members"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].userId").value(3))
                .andExpect(jsonPath("$[0].username").value("member"))
                .andExpect(jsonPath("$[0].role").value("MEMBER"));
    }

    @Test
    @DisplayName("PUT /api/communities/{id}/members/{userId}/role - User cannot elevate their own role (403 Forbidden)")
    void userCannotElevateOwnRole() throws Exception {
        when(communityMemberService.updateMemberRole(10L, 3L, CommunityMemberRole.MODERATOR))
                .thenThrow(new ModeratorRoleChangeException("You cannot change your own role"));

        String jsonRequest = """
                {
                    "role": "MODERATOR"
                }
                """;

        mockMvc.perform(put("/api/communities/10/members/3/role")
                        .header("Authorization", "Bearer " + memberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonRequest))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.message").value("You cannot change your own role"));
    }

    @Test
    @DisplayName("PUT /api/communities/{id}/members/{userId}/role - User cannot modify membership in a community they do not own (403 Forbidden)")
    void userCannotModifyCommunityTheyDoNotOwn() throws Exception {
        when(communityMemberService.updateMemberRole(10L, 3L, CommunityMemberRole.MODERATOR))
                .thenThrow(new ModeratorRoleChangeException("Only the community owner can change member roles"));

        String jsonRequest = """
                {
                    "role": "MODERATOR"
                }
                """;

        mockMvc.perform(put("/api/communities/10/members/3/role")
                        .header("Authorization", "Bearer " + memberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonRequest))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.message").value("Only the community owner can change member roles"));
    }

    @Test
    @DisplayName("PUT /api/communities/{id}/members/{userId}/role - Invalid role is rejected (400 Bad Request)")
    void invalidRoleIsRejected() throws Exception {
        String invalidJsonRequest = """
                {
                    "role": "SUPERMODERATOR"
                }
                """;

        mockMvc.perform(put("/api/communities/10/members/3/role")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidJsonRequest))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @DisplayName("PUT /api/communities/{id}/members/{userId}/role - Missing community returns 404 Not Found")
    void missingCommunityReturnsNotFound() throws Exception {
        when(communityMemberService.updateMemberRole(99L, 3L, CommunityMemberRole.MODERATOR))
                .thenThrow(new CommunityNotFoundException("Community not found with id: 99"));

        String jsonRequest = """
                {
                    "role": "MODERATOR"
                }
                """;

        mockMvc.perform(put("/api/communities/99/members/3/role")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonRequest))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Community not found with id: 99"));
    }

    @Test
    @DisplayName("PUT /api/communities/{id}/members/{userId}/role - Missing target membership returns 400 Bad Request")
    void missingTargetMembershipReturnsBadRequest() throws Exception {
        when(communityMemberService.updateMemberRole(10L, 99L, CommunityMemberRole.MODERATOR))
                .thenThrow(new NotMemberException("User is not a member of this community"));

        String jsonRequest = """
                {
                    "role": "MODERATOR"
                }
                """;

        mockMvc.perform(put("/api/communities/10/members/99/role")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonRequest))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("User is not a member of this community"));
    }

    @Test
    @DisplayName("PUT /api/communities/{id}/members/{userId}/role - Owner role cannot be transferred or removed (403 Forbidden)")
    void ownerRoleCannotBeChanged() throws Exception {
        when(communityMemberService.updateMemberRole(10L, 1L, CommunityMemberRole.MODERATOR))
                .thenThrow(new ModeratorRoleChangeException("Cannot change the role of the community owner"));

        String jsonRequest = """
                {
                    "role": "MODERATOR"
                }
                """;

        mockMvc.perform(put("/api/communities/10/members/1/role")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonRequest))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.message").value("Cannot change the role of the community owner"));
    }

    @Test
    @DisplayName("PUT /api/communities/{id}/members/{userId}/role - Cannot assign OWNER role through endpoint (403 Forbidden)")
    void cannotAssignOwnerRole() throws Exception {
        when(communityMemberService.updateMemberRole(10L, 3L, CommunityMemberRole.OWNER))
                .thenThrow(new ModeratorRoleChangeException("Cannot assign the OWNER role through this endpoint"));

        String jsonRequest = """
                {
                    "role": "OWNER"
                }
                """;

        mockMvc.perform(put("/api/communities/10/members/3/role")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonRequest))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.message").value("Cannot assign the OWNER role through this endpoint"));
    }

    @Test
    @DisplayName("PUT /api/communities/{id}/members/{userId}/role - Unauthenticated request returns 401 Unauthorized")
    void roleChangeRequiresAuthentication() throws Exception {
        mockMvc.perform(put("/api/communities/10/members/3/role")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"MODERATOR\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("PUT /api/communities/{id}/members/{userId}/role - Invalid path parameter type returns 400 Bad Request")
    void invalidPathParameterTypeReturnsBadRequest() throws Exception {
        String jsonRequest = """
                {
                    "role": "MODERATOR"
                }
                """;

        mockMvc.perform(put("/api/communities/invalid-id/members/3/role")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonRequest))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Invalid parameter type for: id"));
    }
}
