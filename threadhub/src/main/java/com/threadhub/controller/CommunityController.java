package com.threadhub.controller;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
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

    // New paginated & sorted endpoint:
    @GetMapping
    public ResponseEntity<Page<CommunityResponse>> getAllCommunities(
            @PageableDefault(page = 0, size = 10, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        Page<CommunityResponse> communities = communityService.getAllCommunities(pageable);
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
}
