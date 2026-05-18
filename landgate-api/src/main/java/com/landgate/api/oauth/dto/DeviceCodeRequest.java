package com.landgate.api.oauth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 设备码授权请求 —— 发起 OpenAI Device Code Flow。
 *
 * @param platform 目标平台，目前仅支持 "openai"
 */
public record DeviceCodeRequest(
        @NotBlank String platform
) {}
