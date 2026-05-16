package com.landgate.api.auth.dto;

import com.landgate.types.enums.Status;
import lombok.Builder;

@Builder
public record ApiKeyResponse(
        Long id,
        String key,
        String name,
        Status status
) {}
