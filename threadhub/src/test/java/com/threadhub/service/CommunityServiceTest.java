package com.threadhub.service;

import com.threadhub.dto.CommunityCreateRequest;
import com.threadhub.dto.CommunityResponse;
import com.threadhub.dto.CommunityUpdateRequest;
import com.threadhub.exception.CommunityNotFoundException;
import com.threadhub.exception.DuplicateCommunityNameException;
import com.threadhub.exception.InvalidCredentialsException;
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
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.InOrder;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CommunityServiceTest {

    @Mock
    private CommunityRepository communityRepository;

    @Mock
    private CommunityMemberRepository communityMemberRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private CommunityService communityService;

    private MockedStatic<SecurityUtils> securityUtilsMock;
    private User testUser;
    private Community sampleCommunity;

    @BeforeEach
    void setUp() {
        securityUtilsMock = mockStatic(SecurityUtils.class);

        testUser = User.builder()
                .id(1L)
                .username("john")
                .email("john@example.com")
                .build();

        sampleCommunity = Community.builder()
                .id(10L)
                .name("java")
                .description("Java programming")
                .owner(testUser)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    @AfterEach
    void tearDown() {
        securityUtilsMock.close();
    }

    @Test
    @DisplayName("Authenticated user should create community and automatically become OWNER member")
    void shouldCreateCommunityAndMakeCreatorOwner() {
        securityUtilsMock.when(SecurityUtils::getCurrentUserId).thenReturn(Optional.of(1L));

        CommunityCreateRequest request = CommunityCreateRequest.builder()
                .name("java")
                .description("Java programming")
                .build();

        when(userRepository.findById(1L)).thenReturn(Optional.of(testUser));
        when(communityRepository.existsByName("java")).thenReturn(false);
        when(communityRepository.save(any(Community.class))).thenReturn(sampleCommunity);

        CommunityResponse response = communityService.createCommunity(request);

        assertThat(response).isNotNull();
        assertThat(response.getId()).isEqualTo(10L);
        assertThat(response.getName()).isEqualTo("java");
        assertThat(response.getOwner().getUsername()).isEqualTo("john");
        assertThat(response.getMemberCount()).isEqualTo(1L);

        ArgumentCaptor<CommunityMember> memberCaptor = ArgumentCaptor.forClass(CommunityMember.class);
        verify(communityMemberRepository, times(1)).save(memberCaptor.capture());

        CommunityMember savedMember = memberCaptor.getValue();
        assertThat(savedMember.getRole()).isEqualTo(CommunityMemberRole.OWNER);
        assertThat(savedMember.getUser().getId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("Should throw DuplicateCommunityNameException when community name exists")
    void shouldThrowExceptionOnDuplicateCommunityName() {
        securityUtilsMock.when(SecurityUtils::getCurrentUserId).thenReturn(Optional.of(1L));

        CommunityCreateRequest request = CommunityCreateRequest.builder()
                .name("java")
                .description("Java programming")
                .build();

        when(userRepository.findById(1L)).thenReturn(Optional.of(testUser));
        when(communityRepository.existsByName("java")).thenReturn(true);

        assertThatThrownBy(() -> communityService.createCommunity(request))
                .isInstanceOf(DuplicateCommunityNameException.class)
                .hasMessageContaining("Community name already taken");

        verify(communityRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should get community by ID")
    void shouldGetCommunityById() {
        when(communityRepository.findById(10L)).thenReturn(Optional.of(sampleCommunity));
        when(communityMemberRepository.countByCommunityId(10L)).thenReturn(5L);

        CommunityResponse response = communityService.getCommunityById(10L);

        assertThat(response).isNotNull();
        assertThat(response.getId()).isEqualTo(10L);
        assertThat(response.getMemberCount()).isEqualTo(5L);
    }

    @Test
    @DisplayName("Should throw CommunityNotFoundException when ID not found")
    void shouldThrowExceptionWhenIdNotFound() {
        when(communityRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> communityService.getCommunityById(99L))
                .isInstanceOf(CommunityNotFoundException.class);
    }

    @Test
    @DisplayName("Should get community by name")
    void shouldGetCommunityByName() {
        when(communityRepository.findByName("java")).thenReturn(Optional.of(sampleCommunity));
        when(communityMemberRepository.countByCommunityId(10L)).thenReturn(3L);

        CommunityResponse response = communityService.getCommunityByName("java");

        assertThat(response).isNotNull();
        assertThat(response.getName()).isEqualTo("java");
        assertThat(response.getMemberCount()).isEqualTo(3L);
    }

    @Test
    @DisplayName("Should list all communities")
    void shouldListCommunities() {
        when(communityRepository.findAll()).thenReturn(List.of(sampleCommunity));
        when(communityMemberRepository.countByCommunityId(10L)).thenReturn(1L);

        List<CommunityResponse> list = communityService.getAllCommunities();

        assertThat(list).hasSize(1);
        assertThat(list.get(0).getName()).isEqualTo("java");
    }

    @Test
    @DisplayName("Community owner should update the community description")
    void shouldUpdateCommunityDescriptionAsOwner() {
        securityUtilsMock.when(SecurityUtils::getCurrentUserId).thenReturn(Optional.of(1L));
        when(communityRepository.findById(10L)).thenReturn(Optional.of(sampleCommunity));
        when(communityRepository.saveAndFlush(any(Community.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(communityMemberRepository.countByCommunityId(10L)).thenReturn(3L);

        CommunityResponse response = communityService.updateCommunity(10L,
                CommunityUpdateRequest.builder().description("Updated description").build());

        assertThat(response.getId()).isEqualTo(10L);
        assertThat(response.getDescription()).isEqualTo("Updated description");
        assertThat(response.getMemberCount()).isEqualTo(3L);
        assertThat(response.getOwner().getUsername()).isEqualTo("john");
        assertThat(response.getCreatedAt()).isNotNull();
        assertThat(sampleCommunity.getDescription()).isEqualTo("Updated description");

        verify(communityRepository, times(1)).saveAndFlush(sampleCommunity);
    }

    @Test
    @DisplayName("Updating a community must never change its name and the update DTO cannot carry one")
    void shouldKeepCommunityNameUnchangedWhenUpdating() {
        securityUtilsMock.when(SecurityUtils::getCurrentUserId).thenReturn(Optional.of(1L));
        when(communityRepository.findById(10L)).thenReturn(Optional.of(sampleCommunity));
        when(communityRepository.saveAndFlush(any(Community.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(communityMemberRepository.countByCommunityId(10L)).thenReturn(1L);

        CommunityResponse response = communityService.updateCommunity(10L,
                CommunityUpdateRequest.builder().description("New description").build());

        assertThat(response.getName()).isEqualTo("java");
        assertThat(sampleCommunity.getName()).isEqualTo("java");

        List<String> updateRequestFields = Arrays.stream(CommunityUpdateRequest.class.getDeclaredFields())
                .map(Field::getName)
                .toList();
        assertThat(updateRequestFields).contains("description");
        assertThat(updateRequestFields).doesNotContain("name", "owner", "id", "createdAt", "updatedAt");
    }

    @Test
    @DisplayName("Blank, empty or omitted descriptions clear the community description")
    void shouldClearDescriptionWhenBlankOrOmitted() {
        securityUtilsMock.when(SecurityUtils::getCurrentUserId).thenReturn(Optional.of(1L));
        when(communityRepository.findById(10L)).thenReturn(Optional.of(sampleCommunity));
        when(communityRepository.saveAndFlush(any(Community.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(communityMemberRepository.countByCommunityId(10L)).thenReturn(1L);

        CommunityResponse blankResponse = communityService.updateCommunity(10L,
                CommunityUpdateRequest.builder().description("   ").build());
        assertThat(blankResponse.getDescription()).isNull();
        assertThat(sampleCommunity.getDescription()).isNull();

        sampleCommunity.setDescription("Java programming");
        CommunityResponse omittedResponse = communityService.updateCommunity(10L,
                CommunityUpdateRequest.builder().build());
        assertThat(omittedResponse.getDescription()).isNull();
        assertThat(sampleCommunity.getDescription()).isNull();
    }

    @Test
    @DisplayName("Surrounding whitespace in a description is trimmed before it is stored")
    void shouldTrimDescriptionWhenUpdating() {
        securityUtilsMock.when(SecurityUtils::getCurrentUserId).thenReturn(Optional.of(1L));
        when(communityRepository.findById(10L)).thenReturn(Optional.of(sampleCommunity));
        when(communityRepository.saveAndFlush(any(Community.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(communityMemberRepository.countByCommunityId(10L)).thenReturn(1L);

        CommunityResponse response = communityService.updateCommunity(10L,
                CommunityUpdateRequest.builder().description("  Java and Spring  ").build());

        assertThat(response.getDescription()).isEqualTo("Java and Spring");
    }

    @Test
    @DisplayName("Non-owner must not be able to update a community")
    void shouldRejectUpdateByNonOwner() {
        securityUtilsMock.when(SecurityUtils::getCurrentUserId).thenReturn(Optional.of(2L));
        when(communityRepository.findById(10L)).thenReturn(Optional.of(sampleCommunity));

        assertThatThrownBy(() -> communityService.updateCommunity(10L,
                CommunityUpdateRequest.builder().description("Hijacked").build()))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("owner");

        assertThat(sampleCommunity.getDescription()).isEqualTo("Java programming");
        verify(communityRepository, never()).saveAndFlush(any(Community.class));
    }

    @Test
    @DisplayName("Updating a nonexistent community throws CommunityNotFoundException")
    void shouldThrowNotFoundWhenUpdatingMissingCommunity() {
        securityUtilsMock.when(SecurityUtils::getCurrentUserId).thenReturn(Optional.of(1L));
        when(communityRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> communityService.updateCommunity(99L,
                CommunityUpdateRequest.builder().description("Anything").build()))
                .isInstanceOf(CommunityNotFoundException.class)
                .hasMessageContaining("Community not found with id: 99");

        verify(communityRepository, never()).saveAndFlush(any(Community.class));
    }

    @Test
    @DisplayName("Unauthenticated callers cannot update a community")
    void shouldRejectUpdateWhenUnauthenticated() {
        securityUtilsMock.when(SecurityUtils::getCurrentUserId).thenReturn(Optional.empty());

        assertThatThrownBy(() -> communityService.updateCommunity(10L,
                CommunityUpdateRequest.builder().description("Anything").build()))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessageContaining("Authentication required");

        verifyNoInteractions(communityRepository);
    }

    @Test
    @DisplayName("Community owner should delete the community and its memberships")
    void shouldDeleteCommunityAndMembershipsAsOwner() {
        securityUtilsMock.when(SecurityUtils::getCurrentUserId).thenReturn(Optional.of(1L));
        when(communityRepository.findById(10L)).thenReturn(Optional.of(sampleCommunity));

        communityService.deleteCommunity(10L);

        InOrder deletionOrder = inOrder(communityMemberRepository, communityRepository);
        deletionOrder.verify(communityMemberRepository, times(1)).deleteByCommunityId(10L);
        deletionOrder.verify(communityRepository, times(1)).delete(sampleCommunity);
    }

    @Test
    @DisplayName("Non-owner must not be able to delete a community")
    void shouldRejectDeleteByNonOwner() {
        securityUtilsMock.when(SecurityUtils::getCurrentUserId).thenReturn(Optional.of(2L));
        when(communityRepository.findById(10L)).thenReturn(Optional.of(sampleCommunity));

        assertThatThrownBy(() -> communityService.deleteCommunity(10L))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("owner");

        verify(communityMemberRepository, never()).deleteByCommunityId(any());
        verify(communityRepository, never()).delete(any(Community.class));
    }

    @Test
    @DisplayName("Deleting a nonexistent community throws CommunityNotFoundException")
    void shouldThrowNotFoundWhenDeletingMissingCommunity() {
        securityUtilsMock.when(SecurityUtils::getCurrentUserId).thenReturn(Optional.of(1L));
        when(communityRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> communityService.deleteCommunity(99L))
                .isInstanceOf(CommunityNotFoundException.class)
                .hasMessageContaining("Community not found with id: 99");

        verify(communityMemberRepository, never()).deleteByCommunityId(any());
        verify(communityRepository, never()).delete(any(Community.class));
    }

    @Test
    @DisplayName("Unauthenticated callers cannot delete a community")
    void shouldRejectDeleteWhenUnauthenticated() {
        securityUtilsMock.when(SecurityUtils::getCurrentUserId).thenReturn(Optional.empty());

        assertThatThrownBy(() -> communityService.deleteCommunity(10L))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessageContaining("Authentication required");

        verifyNoInteractions(communityRepository, communityMemberRepository);
    }

    @Test
    @DisplayName("A failure while deleting the community propagates instead of leaving memberships deleted")
    void shouldNotSwallowFailureDuringCommunityDeletion() {
        securityUtilsMock.when(SecurityUtils::getCurrentUserId).thenReturn(Optional.of(1L));
        when(communityRepository.findById(10L)).thenReturn(Optional.of(sampleCommunity));
        doThrow(new DataIntegrityViolationException("simulated database failure"))
                .when(communityRepository).delete(sampleCommunity);

        assertThatThrownBy(() -> communityService.deleteCommunity(10L))
                .isInstanceOf(DataIntegrityViolationException.class);

        verify(communityMemberRepository, times(1)).deleteByCommunityId(10L);

        // Membership cleanup and the community deletion share the single service-level transaction,
        // so the failure above rolls both statements back together (see the database-backed test).
        Transactional transactional = CommunityService.class.getAnnotation(Transactional.class);
        assertThat(transactional).isNotNull();
        assertThat(transactional.rollbackFor()).isEmpty();
    }
}
