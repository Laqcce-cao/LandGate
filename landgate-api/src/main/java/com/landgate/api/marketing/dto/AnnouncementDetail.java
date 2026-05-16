package com.landgate.api.marketing.dto;

import com.landgate.types.enums.AnnouncementType;
import lombok.Builder;
import java.time.Instant;

@Builder
public record AnnouncementDetail(
        Long id, String title, String content, AnnouncementType type,
        boolean published, Instant publishAt, Instant expiresAt,
        Instant createdAt
) {}
