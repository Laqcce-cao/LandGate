package com.landgate.api.marketing.dto;

import jakarta.validation.constraints.NotBlank;

public record AnnouncementCreateRequest(
        @NotBlank String title,
        @NotBlank String content,
        String type,
        String publishAt,
        String expiresAt,
        Integer sortOrder
) {}
