package com.threadhub.service;

import com.threadhub.dto.CommunityMemberResponse;
import com.threadhub.dto.MembershipStatusResponse;
import com.threadhub.exception.*;
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
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@Transactional
public class CommunityMemberService {

    private final CommunityMemberRepository communityMemberRepository;
    private final CommunityRepository communityRepository;
    private final UserRepository userRepository;

    public CommunityMemberService(CommunityMemberRepository communityMemberRepository,
                                  CommunityRepository communityRepository,
                                  UserRepository userRepository) {
        this.communityMemberRepository = communityMemberRepository;
        this.communityRepository = communityRepository;
        this.userRepository = userRepository;
    }

    public CommunityMemberResponse joinCommunity(Long communityId) {
        Long userId = SecurityUtils.getCurrentUserId()
                .orElseThrow(() -> new InvalidCredentialsException("Authentication required"));

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("User not found with id: " + userId));

        Community community = communityRepository.findById(communityId)
                .orElseThrow(() -> new CommunityNotFoundException("Community not found with id: " + communityId));

        if (communityMemberRepository.existsByCommunityIdAndUserId(communityId, userId)) {
            throw new AlreadyMemberException("User is already a member of this community");
        }

        CommunityMember member = CommunityMember.builder()
                .community(community)
                .user(user)
                .role(CommunityMemberRole.MEMBER)
                .build();

        CommunityMember savedMember = communityMemberRepository.save(member);
        return CommunityMemberResponse.fromEntity(savedMember);
    }

    public void leaveCommunity(Long communityId) {
        Long userId = SecurityUtils.getCurrentUserId()
                .orElseThrow(() -> new InvalidCredentialsException("Authentication required"));

        Community community = communityRepository.findById(communityId)
                .orElseThrow(() -> new CommunityNotFoundException("Community not found with id: " + communityId));

        if (community.getOwner().getId().equals(userId)) {
            throw new OwnerCannotLeaveException("Community owner cannot leave their own community");
        }

        CommunityMember member = communityMemberRepository.findByCommunityIdAndUserId(communityId, userId)
                .orElseThrow(() -> new NotMemberException("User is not a member of this community"));

        communityMemberRepository.delete(member);
    }

    @Transactional(readOnly = true)
    public MembershipStatusResponse getMembershipStatus(Long communityId) {
        Long userId = SecurityUtils.getCurrentUserId()
                .orElseThrow(() -> new InvalidCredentialsException("Authentication required"));

        if (!communityRepository.existsById(communityId)) {
            throw new CommunityNotFoundException("Community not found with id: " + communityId);
        }

        Optional<CommunityMember> membership = communityMemberRepository.findByCommunityIdAndUserId(communityId, userId);

        return MembershipStatusResponse.builder()
                .isMember(membership.isPresent())
                .role(membership.map(CommunityMember::getRole).orElse(null))
                .build();
    }

    @Transactional(readOnly = true)
    public List<CommunityMemberResponse> getCommunityMembers(Long communityId) {
        if (!communityRepository.existsById(communityId)) {
            throw new CommunityNotFoundException("Community not found with id: " + communityId);
        }

        return communityMemberRepository.findByCommunityId(communityId).stream()
                .map(CommunityMemberResponse::fromEntity)
                .collect(Collectors.toList());
    }

    public CommunityMemberResponse updateMemberRole(Long communityId, Long userId, CommunityMemberRole newRole) {
        Long callerId = SecurityUtils.getCurrentUserId()
                .orElseThrow(() -> new InvalidCredentialsException("Authentication required"));

        Community community = communityRepository.findById(communityId)
                .orElseThrow(() -> new CommunityNotFoundException("Community not found with id: " + communityId));

        CommunityMember targetMembership = communityMemberRepository.findByCommunityIdAndUserId(communityId, userId)
                .orElseThrow(() -> new NotMemberException("User is not a member of this community"));

        if (targetMembership.getRole() == CommunityMemberRole.OWNER) {
            throw new ModeratorRoleChangeException("Cannot change the role of the community owner");
        }

        if (callerId.equals(userId)) {
            throw new ModeratorRoleChangeException("You cannot change your own role");
        }

        CommunityMember callerMembership = communityMemberRepository.findByCommunityIdAndUserId(communityId, callerId)
                .orElseThrow(() -> new NotMemberException("You are not a member of this community"));

        if (callerMembership.getRole() != CommunityMemberRole.OWNER) {
            throw new ModeratorRoleChangeException("Only the community owner can change member roles");
        }

        if (newRole == CommunityMemberRole.OWNER) {
            throw new ModeratorRoleChangeException("Cannot assign the OWNER role through this endpoint");
        }

        targetMembership.setRole(newRole);
        CommunityMember saved = communityMemberRepository.save(targetMembership);
        return CommunityMemberResponse.fromEntity(saved);
    }
}
