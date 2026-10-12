package com.threadhub.dto;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request payload for updating an existing community.
 *
 * <p>Only the community description is mutable through this endpoint. The community name, owner,
 * member count and timestamps are deliberately absent from this DTO so a client can never change
 * them through the request body.</p>
 *
 * <p>Semantics (PUT replaces the mutable state of the community):</p>
 * <ul>
 *     <li>a description containing non-whitespace characters is stored trimmed,</li>
 *     <li>a {@code null}, omitted, empty or whitespace-only description clears the description.</li>
 * </ul>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CommunityUpdateRequest {

    @Size(max = 500, message = "Description must not exceed 500 characters")
    private String description;
}
