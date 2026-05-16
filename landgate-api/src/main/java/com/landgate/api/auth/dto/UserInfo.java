package com.landgate.api.auth.dto;

import com.landgate.types.enums.Role;
import com.landgate.types.enums.Status;
import lombok.Builder;

import java.math.BigDecimal;

@Builder
public record UserInfo(
        Long id,
        String email,
        String username,
        Role role,
        Status status,
        BigDecimal balance,
        Integer concurrency
) {}
