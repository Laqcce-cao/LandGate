package com.landgate.api.account.dto;

import java.util.Map;

public record AccountUpdateRequest(
        String name, String platform, String type,
        Map<String, Object> credentials, Map<String, Object> extra,
        Long proxyId, Integer concurrency, Integer priority
) {}
