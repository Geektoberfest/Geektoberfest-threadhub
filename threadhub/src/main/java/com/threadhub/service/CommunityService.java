package com.threadhub.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import com.threadhub.dto.CommunityCreateRequest;
import com.threadhub.dto.CommunityResponse;
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
        Long userId = SecurityUtils.getCurrentUserId()
                .orElseThrow(() -> new InvalidCredentialsException("Authentication required"));

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
