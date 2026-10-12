package com.threadhub.controller;

import com.threadhub.dto.*;
import com.threadhub.service.CommunityMemberService;
import com.threadhub.service.CommunityService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/communities")
public class CommunityController {

    private final CommunityService communityService;
    private final CommunityMemberService communityMemberService;

    public CommunityController(CommunityService communityService, CommunityMemberService communityMemberService) {
        this.communityService = communityService;
        this.communityMemberService = communityMemberService;
    }

    @PostMapping
    public ResponseEntity<CommunityResponse> createCommunity(@Valid @RequestBody CommunityCreateRequest request) {
        CommunityResponse response = communityService.createCommunity(request);
        return new ResponseEntity<>(response, HttpStatus.CREATED);
    }

    @PutMapping("/{id}")
    public ResponseEntity<CommunityResponse> updateCommunity(@PathVariable Long id,
                                                            @Valid @RequestBody CommunityUpdateRequest request) {
        CommunityResponse response = communityService.updateCommunity(id, request);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteCommunity(@PathVariable Long id) {
        communityService.deleteCommunity(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping
    public ResponseEntity<List<CommunityResponse>> getAllCommunities() {
        List<CommunityResponse> communities = communityService.getAllCommunities();
        return ResponseEntity.ok(communities);
    }

    @GetMapping("/{id}")
    public ResponseEntity<CommunityResponse> getCommunityById(@PathVariable Long id) {
        CommunityResponse response = communityService.getCommunityById(id);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/name/{name}")
    public ResponseEntity<CommunityResponse> getCommunityByName(@PathVariable String name) {
        CommunityResponse response = communityService.getCommunityByName(name);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{communityId}/join")
    public ResponseEntity<CommunityMemberResponse> joinCommunity(@PathVariable Long communityId) {
        CommunityMemberResponse response = communityMemberService.joinCommunity(communityId);
        return new ResponseEntity<>(response, HttpStatus.CREATED);
    }

    @DeleteMapping("/{communityId}/leave")
    public ResponseEntity<Void> leaveCommunity(@PathVariable Long communityId) {
        communityMemberService.leaveCommunity(communityId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{communityId}/membership")
    public ResponseEntity<MembershipStatusResponse> getMembershipStatus(@PathVariable Long communityId) {
        MembershipStatusResponse response = communityMemberService.getMembershipStatus(communityId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{communityId}/members")
    public ResponseEntity<List<CommunityMemberResponse>> getCommunityMembers(@PathVariable Long communityId) {
        List<CommunityMemberResponse> members = communityMemberService.getCommunityMembers(communityId);
        return ResponseEntity.ok(members);
    }

    @PutMapping("/{id}/members/{userId}/role")
    public ResponseEntity<CommunityMemberResponse> updateMemberRole(
            @PathVariable Long id,
            @PathVariable Long userId,
            @Valid @RequestBody RoleUpdateRequest request) {
        CommunityMemberResponse response = communityMemberService.updateMemberRole(id, userId, request.getRole());
        return ResponseEntity.ok(response);
    }
}
