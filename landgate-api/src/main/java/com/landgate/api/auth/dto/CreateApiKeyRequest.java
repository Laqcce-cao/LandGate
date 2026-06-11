package com.landgate.api.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateApiKeyRequest(
        @NotBlank String name,
        Long groupId
) {}
