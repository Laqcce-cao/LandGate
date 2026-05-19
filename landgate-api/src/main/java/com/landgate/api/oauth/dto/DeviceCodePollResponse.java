package com.landgate.api.oauth.dto;

/**
 * 设备码轮询响应 —— 返回授权状态和（成功后）创建的 Account 信息。
 *
 * @param status  授权状态: "PENDING" | "EXPIRED" | "SUCCESS"
 * @param account 授权成功时返回创建的 Account 信息，PENDING/EXPIRED 时为 null
 */
public record DeviceCodePollResponse(
        String status,
        OAuthCallbackResponse account
) {}
