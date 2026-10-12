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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
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
import static org.mockito.Mockito.when;
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
