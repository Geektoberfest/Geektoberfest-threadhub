package com.threadhub.service;

import com.threadhub.dto.CommunityMemberResponse;
import com.threadhub.dto.MembershipStatusResponse;
import com.threadhub.exception.AlreadyMemberException;
import com.threadhub.exception.ModeratorRoleChangeException;
import com.threadhub.exception.NotMemberException;
import com.threadhub.exception.OwnerCannotLeaveException;
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
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CommunityMemberServiceTest {

    @Mock
    private CommunityMemberRepository communityMemberRepository;

    @Mock
    private CommunityRepository communityRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private CommunityMemberService communityMemberService;

    private MockedStatic<SecurityUtils> securityUtilsMock;
    private User ownerUser;
    private User memberUser;
    private Community community;

    @BeforeEach
    void setUp() {
        securityUtilsMock = mockStatic(SecurityUtils.class);

        ownerUser = User.builder().id(1L).username("owner").build();
        memberUser = User.builder().id(2L).username("member_user").build();

        community = Community.builder()
                .id(10L)
                .name("java")
                .owner(ownerUser)
                .build();
    }

    @AfterEach
    void tearDown() {
        securityUtilsMock.close();
    }

    @Test
    @DisplayName("Authenticated user should join community with MEMBER role")
    void shouldJoinCommunity() {
        securityUtilsMock.when(SecurityUtils::getCurrentUserId).thenReturn(Optional.of(2L));

        when(userRepository.findById(2L)).thenReturn(Optional.of(memberUser));
        when(communityRepository.findById(10L)).thenReturn(Optional.of(community));
        when(communityMemberRepository.existsByCommunityIdAndUserId(10L, 2L)).thenReturn(false);

        CommunityMember savedMember = CommunityMember.builder()
                .id(100L)
                .community(community)
                .user(memberUser)
                .role(CommunityMemberRole.MEMBER)
                .joinedAt(LocalDateTime.now())
                .build();

        when(communityMemberRepository.save(any(CommunityMember.class))).thenReturn(savedMember);

        CommunityMemberResponse response = communityMemberService.joinCommunity(10L);

        assertThat(response).isNotNull();
        assertThat(response.getUserId()).isEqualTo(2L);
        assertThat(response.getUsername()).isEqualTo("member_user");
        assertThat(response.getRole()).isEqualTo(CommunityMemberRole.MEMBER);
    }

    @Test
    @DisplayName("Should throw AlreadyMemberException on duplicate join")
    void shouldThrowExceptionOnDuplicateJoin() {
        securityUtilsMock.when(SecurityUtils::getCurrentUserId).thenReturn(Optional.of(2L));

        when(userRepository.findById(2L)).thenReturn(Optional.of(memberUser));
        when(communityRepository.findById(10L)).thenReturn(Optional.of(community));
        when(communityMemberRepository.existsByCommunityIdAndUserId(10L, 2L)).thenReturn(true);

        assertThatThrownBy(() -> communityMemberService.joinCommunity(10L))
                .isInstanceOf(AlreadyMemberException.class)
                .hasMessageContaining("already a member");
    }

    @Test
    @DisplayName("Member should leave community successfully")
    void shouldLeaveCommunity() {
        securityUtilsMock.when(SecurityUtils::getCurrentUserId).thenReturn(Optional.of(2L));

        CommunityMember member = CommunityMember.builder()
                .id(100L)
                .community(community)
                .user(memberUser)
                .role(CommunityMemberRole.MEMBER)
                .build();

        when(communityRepository.findById(10L)).thenReturn(Optional.of(community));
        when(communityMemberRepository.findByCommunityIdAndUserId(10L, 2L)).thenReturn(Optional.of(member));

        communityMemberService.leaveCommunity(10L);

        verify(communityMemberRepository, times(1)).delete(member);
    }

    @Test
    @DisplayName("Should throw NotMemberException when non-member tries to leave")
    void shouldThrowExceptionWhenNonMemberLeaves() {
        securityUtilsMock.when(SecurityUtils::getCurrentUserId).thenReturn(Optional.of(2L));

        when(communityRepository.findById(10L)).thenReturn(Optional.of(community));
        when(communityMemberRepository.findByCommunityIdAndUserId(10L, 2L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> communityMemberService.leaveCommunity(10L))
                .isInstanceOf(NotMemberException.class)
                .hasMessageContaining("not a member");
    }

    @Test
    @DisplayName("Should throw OwnerCannotLeaveException when owner tries to leave")
    void shouldThrowExceptionWhenOwnerLeaves() {
        securityUtilsMock.when(SecurityUtils::getCurrentUserId).thenReturn(Optional.of(1L));

        when(communityRepository.findById(10L)).thenReturn(Optional.of(community));

        assertThatThrownBy(() -> communityMemberService.leaveCommunity(10L))
                .isInstanceOf(OwnerCannotLeaveException.class)
                .hasMessageContaining("owner cannot leave");

        verify(communityMemberRepository, never()).delete(any());
    }

    @Test
    @DisplayName("Should return membership status for authenticated user")
    void shouldGetMembershipStatus() {
        securityUtilsMock.when(SecurityUtils::getCurrentUserId).thenReturn(Optional.of(2L));

        CommunityMember member = CommunityMember.builder()
                .community(community)
                .user(memberUser)
                .role(CommunityMemberRole.MEMBER)
                .build();

        when(communityRepository.existsById(10L)).thenReturn(true);
        when(communityMemberRepository.findByCommunityIdAndUserId(10L, 2L)).thenReturn(Optional.of(member));

        MembershipStatusResponse status = communityMemberService.getMembershipStatus(10L);

        assertThat(status.isMember()).isTrue();
        assertThat(status.getRole()).isEqualTo(CommunityMemberRole.MEMBER);
    }

    @Test
    @DisplayName("Should list members for a community")
    void shouldListCommunityMembers() {
        CommunityMember member = CommunityMember.builder()
                .community(community)
                .user(memberUser)
                .role(CommunityMemberRole.MEMBER)
                .joinedAt(LocalDateTime.now())
                .build();

        when(communityRepository.existsById(10L)).thenReturn(true);
        when(communityMemberRepository.findByCommunityId(10L)).thenReturn(List.of(member));

        List<CommunityMemberResponse> members = communityMemberService.getCommunityMembers(10L);

        assertThat(members).hasSize(1);
        assertThat(members.get(0).getUsername()).isEqualTo("member_user");
    }

    // --- Role changes (RBAC) ---

    @Test
    @DisplayName("Owner changing their own role gets the self-change error, not the owner-protection error")
    void ownerChangingOwnRoleGetsSelfChangeError() {
        securityUtilsMock.when(SecurityUtils::getCurrentUserId).thenReturn(Optional.of(1L));

        // The caller is the OWNER and is also the target, so both guards apply at once:
        // the self-change check has to run first for the caller to see the right message.
        CommunityMember ownerMembership = membership(community, ownerUser, CommunityMemberRole.OWNER);

        when(communityRepository.findById(10L)).thenReturn(Optional.of(community));
        when(communityMemberRepository.findByCommunityIdAndUserId(10L, 1L))
                .thenReturn(Optional.of(ownerMembership));

        assertThatThrownBy(() -> communityMemberService.updateMemberRole(10L, 1L, CommunityMemberRole.MODERATOR))
                .isInstanceOf(ModeratorRoleChangeException.class)
                .hasMessage("You cannot change your own role");

        verify(communityMemberRepository, never()).save(any(CommunityMember.class));
    }

    @Test
    @DisplayName("Owner targeting a different OWNER still gets the owner-protection error")
    void ownerProtectsAnotherOwnerFromRoleChange() {
        securityUtilsMock.when(SecurityUtils::getCurrentUserId).thenReturn(Optional.of(1L));

        User otherOwner = User.builder().id(4L).username("other_owner").build();
        CommunityMember otherOwnerMembership = membership(community, otherOwner, CommunityMemberRole.OWNER);

        when(communityRepository.findById(10L)).thenReturn(Optional.of(community));
        when(communityMemberRepository.findByCommunityIdAndUserId(10L, 4L))
                .thenReturn(Optional.of(otherOwnerMembership));

        assertThatThrownBy(() -> communityMemberService.updateMemberRole(10L, 4L, CommunityMemberRole.MEMBER))
                .isInstanceOf(ModeratorRoleChangeException.class)
                .hasMessage("Cannot change the role of the community owner");

        verify(communityMemberRepository, never()).save(any(CommunityMember.class));
    }

    @Test
    @DisplayName("Owner can promote a member to MODERATOR")
    void ownerPromotesMemberToModerator() {
        securityUtilsMock.when(SecurityUtils::getCurrentUserId).thenReturn(Optional.of(1L));

        CommunityMember memberMembership = membership(community, memberUser, CommunityMemberRole.MEMBER);

        when(communityRepository.findById(10L)).thenReturn(Optional.of(community));
        when(communityMemberRepository.findByCommunityIdAndUserId(10L, 2L))
                .thenReturn(Optional.of(memberMembership));
        when(communityMemberRepository.findByCommunityIdAndUserId(10L, 1L))
                .thenReturn(Optional.of(membership(community, ownerUser, CommunityMemberRole.OWNER)));
        when(communityMemberRepository.save(any(CommunityMember.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        CommunityMemberResponse response =
                communityMemberService.updateMemberRole(10L, 2L, CommunityMemberRole.MODERATOR);

        assertThat(response.getRole()).isEqualTo(CommunityMemberRole.MODERATOR);
        assertThat(memberMembership.getRole()).isEqualTo(CommunityMemberRole.MODERATOR);
    }

    @Test
    @DisplayName("Owner can demote a moderator to MEMBER")
    void ownerDemotesModeratorToMember() {
        securityUtilsMock.when(SecurityUtils::getCurrentUserId).thenReturn(Optional.of(1L));

        CommunityMember moderatorMembership = membership(community, memberUser, CommunityMemberRole.MODERATOR);

        when(communityRepository.findById(10L)).thenReturn(Optional.of(community));
        when(communityMemberRepository.findByCommunityIdAndUserId(10L, 2L))
                .thenReturn(Optional.of(moderatorMembership));
        when(communityMemberRepository.findByCommunityIdAndUserId(10L, 1L))
                .thenReturn(Optional.of(membership(community, ownerUser, CommunityMemberRole.OWNER)));
        when(communityMemberRepository.save(any(CommunityMember.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        CommunityMemberResponse response =
                communityMemberService.updateMemberRole(10L, 2L, CommunityMemberRole.MEMBER);

        assertThat(response.getRole()).isEqualTo(CommunityMemberRole.MEMBER);
    }

    @Test
    @DisplayName("Non-owner cannot change another member's role")
    void nonOwnerCannotChangeRole() {
        securityUtilsMock.when(SecurityUtils::getCurrentUserId).thenReturn(Optional.of(2L));

        User target = User.builder().id(3L).username("target_user").build();

        when(communityRepository.findById(10L)).thenReturn(Optional.of(community));
        when(communityMemberRepository.findByCommunityIdAndUserId(10L, 3L))
                .thenReturn(Optional.of(membership(community, target, CommunityMemberRole.MEMBER)));
        when(communityMemberRepository.findByCommunityIdAndUserId(10L, 2L))
                .thenReturn(Optional.of(membership(community, memberUser, CommunityMemberRole.MEMBER)));

        assertThatThrownBy(() -> communityMemberService.updateMemberRole(10L, 3L, CommunityMemberRole.MODERATOR))
                .isInstanceOf(ModeratorRoleChangeException.class)
                .hasMessage("Only the community owner can change member roles");

        verify(communityMemberRepository, never()).save(any(CommunityMember.class));
    }

    @Test
    @DisplayName("OWNER role cannot be assigned through the role-change endpoint")
    void ownerRoleCannotBeAssigned() {
        securityUtilsMock.when(SecurityUtils::getCurrentUserId).thenReturn(Optional.of(1L));

        when(communityRepository.findById(10L)).thenReturn(Optional.of(community));
        when(communityMemberRepository.findByCommunityIdAndUserId(10L, 2L))
                .thenReturn(Optional.of(membership(community, memberUser, CommunityMemberRole.MEMBER)));
        when(communityMemberRepository.findByCommunityIdAndUserId(10L, 1L))
                .thenReturn(Optional.of(membership(community, ownerUser, CommunityMemberRole.OWNER)));

        assertThatThrownBy(() -> communityMemberService.updateMemberRole(10L, 2L, CommunityMemberRole.OWNER))
                .isInstanceOf(ModeratorRoleChangeException.class)
                .hasMessage("Cannot assign the OWNER role through this endpoint");

        verify(communityMemberRepository, never()).save(any(CommunityMember.class));
    }

    @Test
    @DisplayName("Changing the role of a non-member returns NotMemberException")
    void changingRoleOfNonMemberReturnsNotFound() {
        securityUtilsMock.when(SecurityUtils::getCurrentUserId).thenReturn(Optional.of(1L));

        when(communityRepository.findById(10L)).thenReturn(Optional.of(community));
        when(communityMemberRepository.findByCommunityIdAndUserId(10L, 2L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> communityMemberService.updateMemberRole(10L, 2L, CommunityMemberRole.MODERATOR))
                .isInstanceOf(NotMemberException.class)
                .hasMessageContaining("not a member");

        verify(communityMemberRepository, never()).save(any(CommunityMember.class));
    }

    private static CommunityMember membership(Community community, User user, CommunityMemberRole role) {
        return CommunityMember.builder()
                .community(community)
                .user(user)
                .role(role)
                .joinedAt(LocalDateTime.now())
                .build();
    }
}
