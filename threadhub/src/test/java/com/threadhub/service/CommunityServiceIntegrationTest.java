package com.threadhub.service;

import com.threadhub.dto.CommunityResponse;
import com.threadhub.dto.CommunityUpdateRequest;
import com.threadhub.model.Community;
import com.threadhub.model.CommunityMember;
import com.threadhub.model.CommunityMemberRole;
import com.threadhub.model.User;
import com.threadhub.repository.CommunityMemberRepository;
import com.threadhub.repository.CommunityRepository;
import com.threadhub.repository.UserRepository;
import com.threadhub.security.SecurityUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.MockedStatic;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockStatic;

/**
 * Database-backed tests for the community update and deletion flow. Unlike the mocked service
 * tests, these run against a real database so membership cleanup and transaction rollback can be
 * verified on persisted rows. The test class is intentionally not transactional so that commits
 * and rollbacks of the service actually happen; data is removed again in {@link #cleanUp()}.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "jwt.secret=404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970"
})
class CommunityServiceIntegrationTest {

    @Autowired
    private CommunityService communityService;

    @MockitoSpyBean
    private CommunityRepository communityRepository;

    @Autowired
    private CommunityMemberRepository communityMemberRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private final List<Long> createdCommunityIds = new ArrayList<>();
    private final List<Long> createdUserIds = new ArrayList<>();

    private User owner;
    private long uniqueSuffix;

    @BeforeEach
    void setUp() {
        uniqueSuffix = System.nanoTime();
        owner = persistUser("it_owner");
    }

    @AfterEach
    void cleanUp() {
        // Restore the real repository before removing the rows created by a test, in case the test
        // stubbed a repository method to simulate a database failure.
        Mockito.reset(communityRepository);

        transactionTemplate.executeWithoutResult(status -> {
            for (Long communityId : createdCommunityIds) {
                communityMemberRepository.deleteByCommunityId(communityId);
                communityRepository.findById(communityId).ifPresent(communityRepository::delete);
            }
        });
        createdCommunityIds.clear();

        createdUserIds.forEach(userRepository::deleteById);
        createdUserIds.clear();
    }

    @Test
    @DisplayName("Deleting a community removes the community and every membership row")
    void shouldDeleteCommunityAndMembershipsTogether() {
        Community community = persistCommunity("it_delete_happy");
        CommunityMember ownerMembership = persistMembership(community, owner, CommunityMemberRole.OWNER);
        User joiner = persistUser("it_joiner");
        CommunityMember joinerMembership = persistMembership(community, joiner, CommunityMemberRole.MEMBER);

        runAuthenticatedAs(owner.getId(), () -> communityService.deleteCommunity(community.getId()));
        createdCommunityIds.remove(community.getId());

        assertThat(communityRepository.findById(community.getId())).isEmpty();
        assertThat(communityMemberRepository.findById(ownerMembership.getId())).isEmpty();
        assertThat(communityMemberRepository.findById(joinerMembership.getId())).isEmpty();
        assertThat(communityMemberRepository.countByCommunityId(community.getId())).isZero();
    }

    @Test
    @DisplayName("A failure while deleting the community rolls the membership cleanup back")
    void shouldRollBackMembershipCleanupWhenCommunityDeletionFails() {
        Community community = persistCommunity("it_delete_rollback");
        CommunityMember membership = persistMembership(community, owner, CommunityMemberRole.OWNER);

        doThrow(new DataIntegrityViolationException("simulated failure while deleting the community"))
                .when(communityRepository).delete(any(Community.class));

        assertThatThrownBy(() -> runAuthenticatedAs(owner.getId(),
                () -> communityService.deleteCommunity(community.getId())))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(communityRepository.existsById(community.getId())).isTrue();
        assertThat(communityMemberRepository.findById(membership.getId())).isPresent();
    }

    @Test
    @DisplayName("Updating a description persists the trimmed value and keeps the community name")
    void shouldUpdateDescriptionAndPreserveName() {
        Community community = persistCommunity("it_update");
        persistMembership(community, owner, CommunityMemberRole.OWNER);

        CommunityResponse response = callAuthenticatedAs(owner.getId(),
                () -> communityService.updateCommunity(community.getId(),
                        CommunityUpdateRequest.builder().description("  Fresh description  ").build()));

        assertThat(response.getDescription()).isEqualTo("Fresh description");
        assertThat(response.getName()).isEqualTo(community.getName());
        assertThat(response.getMemberCount()).isEqualTo(1L);
        assertThat(response.getUpdatedAt()).isNotNull();

        Community reloaded = communityRepository.findById(community.getId()).orElseThrow();
        assertThat(reloaded.getName()).isEqualTo(community.getName());
        assertThat(reloaded.getDescription()).isEqualTo("Fresh description");
    }

    private User persistUser(String prefix) {
        User user = userRepository.save(User.builder()
                .username(prefix + "_" + uniqueSuffix)
                .email(prefix + "_" + uniqueSuffix + "@example.com")
                .password("encoded-password")
                .build());
        createdUserIds.add(user.getId());
        return user;
    }

    private Community persistCommunity(String prefix) {
        Community community = communityRepository.save(Community.builder()
                .name(prefix + "_" + uniqueSuffix)
                .description("Seed description")
                .owner(owner)
                .build());
        createdCommunityIds.add(community.getId());
        return community;
    }

    private CommunityMember persistMembership(Community community, User user, CommunityMemberRole role) {
        return communityMemberRepository.save(CommunityMember.builder()
                .community(community)
                .user(user)
                .role(role)
                .build());
    }

    private void runAuthenticatedAs(Long userId, Runnable action) {
        try (MockedStatic<SecurityUtils> securityUtils = mockStatic(SecurityUtils.class)) {
            securityUtils.when(SecurityUtils::getCurrentUserId).thenReturn(Optional.of(userId));
            action.run();
        }
    }

    private <T> T callAuthenticatedAs(Long userId, Supplier<T> action) {
        try (MockedStatic<SecurityUtils> securityUtils = mockStatic(SecurityUtils.class)) {
            securityUtils.when(SecurityUtils::getCurrentUserId).thenReturn(Optional.of(userId));
            return action.get();
        }
    }
}
