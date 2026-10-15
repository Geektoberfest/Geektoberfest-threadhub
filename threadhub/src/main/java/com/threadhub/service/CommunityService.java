package com.threadhub.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import com.threadhub.dto.CommunityCreateRequest;
import com.threadhub.dto.CommunityResponse;
import com.threadhub.dto.CommunityUpdateRequest;
import com.threadhub.exception.CommunityNotFoundException;
import com.threadhub.exception.DuplicateCommunityNameException;
import com.threadhub.exception.InvalidCredentialsException;
import com.threadhub.exception.UserNotFoundException;
import com.threadhub.model.Community;
import com.threadhub.model.CommunityMember;
import com.threadhub.model.CommunityMemberRole;
import com.threadhub.model.User;
import com.threadhub.repository.CommunityMemberRepository;
import com.threadhub.repository.CommunityRepository;
import com.threadhub.repository.UserRepository;
import com.threadhub.security.SecurityUtils;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@Transactional
public class CommunityService {

    private final CommunityRepository communityRepository;
    private final CommunityMemberRepository communityMemberRepository;
    private final UserRepository userRepository;

    public CommunityService(CommunityRepository communityRepository,
                            CommunityMemberRepository communityMemberRepository,
                            UserRepository userRepository) {
        this.communityRepository = communityRepository;
        this.communityMemberRepository = communityMemberRepository;
        this.userRepository = userRepository;
    }

    public CommunityResponse createCommunity(CommunityCreateRequest request) {
        Long userId = requireAuthenticatedUserId();

        User owner = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("User not found with id: " + userId));

        if (communityRepository.existsByName(request.getName())) {
            throw new DuplicateCommunityNameException("Community name already taken: " + request.getName());
        }

        Community community = Community.builder()
                .name(request.getName())
                .description(request.getDescription())
                .owner(owner)
                .build();

        Community savedCommunity = communityRepository.save(community);

        CommunityMember ownerMember = CommunityMember.builder()
                .community(savedCommunity)
                .user(owner)
                .role(CommunityMemberRole.OWNER)
                .build();

        communityMemberRepository.save(ownerMember);

        return CommunityResponse.fromEntity(savedCommunity, 1L);
    }

    /**
     * Updates the description of an existing community. Only the community owner may update it and
     * the community name can never be changed here.
     *
     * @param id      the community to update
     * @param request payload holding the new description (name, owner and timestamps are ignored)
     * @return the updated community representation
     * @throws CommunityNotFoundException if no community exists with the given id
     * @throws AccessDeniedException      if the authenticated user is not the community owner
     * @throws InvalidCredentialsException if the request is not authenticated
     */
    public CommunityResponse updateCommunity(Long id, CommunityUpdateRequest request) {
        Long userId = requireAuthenticatedUserId();

        Community community = communityRepository.findById(id)
                .orElseThrow(() -> new CommunityNotFoundException("Community not found with id: " + id));

        verifyOwner(community, userId, "update");

        community.setDescription(normalizeDescription(request.getDescription()));
        Community updatedCommunity = communityRepository.saveAndFlush(community);

        long memberCount = communityMemberRepository.countByCommunityId(id);
        return CommunityResponse.fromEntity(updatedCommunity, memberCount);
    }

    /**
     * Deletes a community together with every membership it owns. Only the community owner may
     * delete it. Memberships are removed first, inside the same transaction as the community
     * deletion, so that no orphaned membership rows or foreign-key violations are possible and so
     * that a failure rolls both deletions back together.
     *
     * @param id the community to delete
     * @throws CommunityNotFoundException if no community exists with the given id
     * @throws AccessDeniedException      if the authenticated user is not the community owner
     * @throws InvalidCredentialsException if the request is not authenticated
     */
    public void deleteCommunity(Long id) {
        Long userId = requireAuthenticatedUserId();

        Community community = communityRepository.findById(id)
                .orElseThrow(() -> new CommunityNotFoundException("Community not found with id: " + id));

        verifyOwner(community, userId, "delete");

        communityMemberRepository.deleteByCommunityId(id);
        communityRepository.delete(community);
    }

    private Long requireAuthenticatedUserId() {
        return SecurityUtils.getCurrentUserId()
                .orElseThrow(() -> new InvalidCredentialsException("Authentication required"));
    }

    /**
     * Guards owner-only operations using the authenticated user id, never an id supplied by the
     * caller through the URL or request body.
     */
    private void verifyOwner(Community community, Long userId, String action) {
        if (!userId.equals(community.getOwner().getId())) {
            throw new AccessDeniedException("Only the community owner can " + action + " this community");
        }
    }

    private String normalizeDescription(String description) {
        if (description == null || description.isBlank()) {
            return null;
        }
        return description.trim();
    }

    @Transactional(readOnly = true)
    public CommunityResponse getCommunityById(Long id) {
        Community community = communityRepository.findById(id)
                .orElseThrow(() -> new CommunityNotFoundException("Community not found with id: " + id));

        long memberCount = communityMemberRepository.countByCommunityId(id);
        return CommunityResponse.fromEntity(community, memberCount);
    }

    @Transactional(readOnly = true)
    public CommunityResponse getCommunityByName(String name) {
        Community community = communityRepository.findByName(name)
                .orElseThrow(() -> new CommunityNotFoundException("Community not found with name: " + name));

        long memberCount = communityMemberRepository.countByCommunityId(community.getId());
        return CommunityResponse.fromEntity(community, memberCount);
    }

    // New paginated method:
    @Transactional(readOnly = true)
    public Page<CommunityResponse> getAllCommunities(Pageable pageable) {
        return communityRepository.findAll(pageable)
                .map(community -> {
                    long memberCount = communityMemberRepository.countByCommunityId(community.getId());
                    return CommunityResponse.fromEntity(community, memberCount);
                });
    }
}
