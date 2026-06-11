package com.landgate.trigger.gateway.access;

import com.landgate.domain.auth.model.entity.UserEntity;
import com.landgate.domain.group.model.entity.GroupEntity;

public record GatewayAccessResult(
        boolean shouldStop,
        Long apiKeyId,
        Long userId,
        Long groupId,
        GroupEntity group,
        UserEntity user
) {
}
