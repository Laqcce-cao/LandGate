package com.landgate.api.marketing.dto;

public record AnnouncementUpdateRequest(
        String title, String content, String type,
        String publishAt, String expiresAt, Integer sortOrder
) {}
