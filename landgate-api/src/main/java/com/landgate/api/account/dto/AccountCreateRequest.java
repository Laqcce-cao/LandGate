package com.landgate.api.account.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.Map;

public record AccountCreateRequest(
        @NotBlank String name,
        @NotBlank String platform,
        @NotBlank String type,
        Map<String, Object> credentials,
        Map<String, Object> extra,
        Long proxyId,
        Integer concurrency,
        Integer priority
) {}
