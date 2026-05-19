package com.landgate.api.oauth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 设备码轮询请求 —— 前端定时轮询用户是否已完成授权。
 *
 * @param deviceAuthId 设备授权 ID
 * @param userCode     用户验证码
 */
public record DeviceCodePollRequest(
        @NotBlank String deviceAuthId,
        @NotBlank String userCode
) {}
