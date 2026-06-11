package com.landgate.types.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * OAuth 提供商枚举 —— 标识支持 OAuth 自动获取凭证的上游 AI 平台。
 * <p>
 * 每个枚举值对应一个平台的 OAuth 配置（endpoints、client_id 等），
 * 实际配置值由 {@code application.yml} 中的 {@code landgate.oauth.providers} 段提供。
 */
@Getter
@AllArgsConstructor
public enum OAuthProvider {

    /** Anthropic —— 使用 Authorization Code + PKCE 流程，通过 platform.claude.com 获取 API 凭证 */
    ANTHROPIC("anthropic", "Anthropic OAuth"),
    /** OpenAI —— 使用 Authorization Code + PKCE 流程，通过 platform.openai.com 获取 API 凭证 */
    OPENAI("openai", "OpenAI OAuth");

    /** 平台标识字符串，对应 {@code application.yml} 中 providers 的 key */
    @JsonValue
    private final String key;
    /** 中文描述 */
    private final String desc;

    @JsonCreator
    public static OAuthProvider from(String value) {
        if (value == null) return null;
        for (OAuthProvider p : values()) {
            if (p.key.equalsIgnoreCase(value) || p.name().equalsIgnoreCase(value)) {
                return p;
            }
        }
        return null;
    }
}
