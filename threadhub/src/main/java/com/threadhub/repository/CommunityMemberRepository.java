package com.threadhub.repository;

import com.threadhub.model.CommunityMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CommunityMemberRepository extends JpaRepository<CommunityMember, Long> {

    Optional<CommunityMember> findByCommunityIdAndUserId(Long communityId, Long userId);

    boolean existsByCommunityIdAndUserId(Long communityId, Long userId);

    void deleteByCommunityIdAndUserId(Long communityId, Long userId);

    /**
     * Deletes every membership of a community in a single statement. Used when a community is
     * deleted so that no orphaned membership rows (or foreign-key violations) can remain.
     *
     * @param communityId the community whose memberships should be removed
     */
    @Modifying
    @Query("delete from CommunityMember cm where cm.community.id = :communityId")
    void deleteByCommunityId(@Param("communityId") Long communityId);

    List<CommunityMember> findByCommunityId(Long communityId);

    long countByCommunityId(Long communityId);
}
