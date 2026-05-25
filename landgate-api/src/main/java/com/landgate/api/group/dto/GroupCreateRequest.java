package com.landgate.api.group.dto;

import jakarta.validation.constraints.NotBlank;

public record GroupCreateRequest(
        @NotBlank String name,
        String subscriptionType,
        String description
) {}
