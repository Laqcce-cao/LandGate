package com.landgate.api.oauth.dto;

import com.landgate.types.enums.AccountType;
import com.landgate.types.enums.Platform;
import com.landgate.types.enums.Status;

/**
 * OAuth 回调响应 —— token 交换成功后创建的 Account 信息。
 *
 * @param accountId 新创建的账号 ID
 * @param name      自动生成的账号名称
 * @param platform  账号所属 AI 平台
 * @param type      认证类型（固定为 OAUTH）
 * @param status    账号初始状态（ACTIVE）
 * @param expiresAt access_token 的过期时间（ISO-8601 格式）
 */
public record OAuthCallbackResponse(
        Long accountId,
        String name,
        Platform platform,
        AccountType type,
        Status status,
        String expiresAt
) {}
