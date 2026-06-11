package com.landgate.types.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * AI 账号认证类型枚举 —— 对应 Go 版本的 account type。
 * <p>
 * 描述上游账号的认证方式，影响网关请求时 token 获取策略。
 */
@Getter
@AllArgsConstructor
public enum AccountType {

    /** OAuth 认证 —— 通过 OAuth 流程获取访问令牌，需 ClientID/Secret + RefreshToken */
    OAUTH("oauth", 1, "OAuth 认证"),
    /** Setup Token 认证 —— 使用预设 Token 直连，常用于 Anthropic Console 分发的 token */
    SETUP_TOKEN("setup_token", 2, "Setup Token 认证"),
    /** API Key 认证 —— 使用固定 API Key 认证，如 OpenAI 的 sk-xxx */
    API_KEY("api_key", 3, "API Key 认证"),
    /** 上游代理转发 —— 透传到另一个兼容 API 的上游服务 */
    UPSTREAM("upstream", 4, "上游代理转发"),
    /** AWS Bedrock —— 使用 AWS IAM 凭证调用 Bedrock 服务 */
    BEDROCK("bedrock", 5, "AWS Bedrock"),
    /** GCP Service Account —— 使用 GCP 服务账号密钥调用 Vertex AI */
    SERVICE_ACCOUNT("service_account", 6, "GCP Service Account");

    /** 代码标识 */
    @JsonValue
    private final String key;
    /** 数值编码 */
    private final Integer value;
    /** 中文描述 */
    private final String desc;

    @JsonCreator
    public static AccountType from(String value) {
        if (value == null) return null;
        for (AccountType t : values()) {
            if (t.key.equalsIgnoreCase(value) || t.name().equalsIgnoreCase(value)) {
                return t;
            }
        }
        throw new IllegalArgumentException("Unknown account type: " + value);
    }
}
