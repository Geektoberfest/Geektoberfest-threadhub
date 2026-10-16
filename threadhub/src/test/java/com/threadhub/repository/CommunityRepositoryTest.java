package com.threadhub.repository;

import com.threadhub.model.Community;
import com.threadhub.model.CommunityMember;
import com.threadhub.model.CommunityMemberRole;
import com.threadhub.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@Transactional
class CommunityRepositoryTest {

    @Autowired
    private CommunityRepository communityRepository;

    @Autowired
    private CommunityMemberRepository communityMemberRepository;

    @Autowired
    private UserRepository userRepository;

    private User owner;

    @BeforeEach
    void setUp() {
        owner = userRepository.save(User.builder()
                .username("comm_owner")
                .email("owner@example.com")
                .password("encodedpass")
                .build());
    }

    @Test
    @DisplayName("Should save community and find by name")
    void shouldSaveAndFindByName() {
        Community community = Community.builder()
                .name("java")
                .description("Java discussion community")
                .owner(owner)
                .build();

        Community saved = communityRepository.save(community);

        assertThat(saved.getId()).isNotNull();
        assertThat(communityRepository.existsByName("java")).isTrue();

        Optional<Community> found = communityRepository.findByName("java");
        assertThat(found).isPresent();
        assertThat(found.get().getDescription()).isEqualTo("Java discussion community");
    }

    @Test
    @DisplayName("Should throw DataIntegrityViolationException on duplicate community name")
    void shouldThrowExceptionOnDuplicateName() {
        Community c1 = Community.builder().name("spring").owner(owner).build();
        communityRepository.saveAndFlush(c1);

        Community c2 = Community.builder().name("spring").owner(owner).build();
        assertThatThrownBy(() -> communityRepository.saveAndFlush(c2))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Should check membership existence and find membership by user and community")
    void shouldManageMemberships() {
        Community community = communityRepository.save(Community.builder()
                .name("react")
                .owner(owner)
                .build());

        CommunityMember member = CommunityMember.builder()
                .community(community)
                .user(owner)
                .role(CommunityMemberRole.OWNER)
                .build();

        communityMemberRepository.save(member);

        assertThat(communityMemberRepository.existsByCommunityIdAndUserId(community.getId(), owner.getId())).isTrue();

        Optional<CommunityMember> foundMember = communityMemberRepository.findByCommunityIdAndUserId(community.getId(), owner.getId());
        assertThat(foundMember).isPresent();
        assertThat(foundMember.get().getRole()).isEqualTo(CommunityMemberRole.OWNER);
    }

    @Test
    @DisplayName("Should throw DataIntegrityViolationException on duplicate membership constraint")
    void shouldEnforceDuplicateMembershipConstraint() {
        Community community = communityRepository.save(Community.builder()
                .name("kotlin")
                .owner(owner)
                .build());

        CommunityMember m1 = CommunityMember.builder()
                .community(community)
                .user(owner)
                .role(CommunityMemberRole.OWNER)
                .build();
        communityMemberRepository.saveAndFlush(m1);

        CommunityMember m2 = CommunityMember.builder()
                .community(community)
                .user(owner)
                .role(CommunityMemberRole.MEMBER)
                .build();

        assertThatThrownBy(() -> communityMemberRepository.saveAndFlush(m2))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
