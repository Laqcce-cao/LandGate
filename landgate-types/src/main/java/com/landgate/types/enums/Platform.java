package com.landgate.types.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * AI 平台枚举 —— 对应 Go 版本的 platform 常量。
 * <p>
 * 标识上游 AI 服务提供商，用于账号归属、分组平台限制等场景。
 */
@Getter
@AllArgsConstructor
public enum Platform {

    /** Anthropic Claude —— 支持 Messages / Text Completions API */
    ANTHROPIC("anthropic", 1, "Anthropic Claude"),
    /** OpenAI —— 支持 Chat Completions / Embeddings API */
    OPENAI("openai", 2, "OpenAI"),
    /** Google Gemini —— 支持 Gemini API */
    GEMINI("gemini", 3, "Google Gemini"),
    /** Antigravity —— 自定义代理平台 */
    ANTIGRAVITY("antigravity", 4, "Antigravity"),
    /** OpenAI Responses API —— 新版语义 API（Codex CLI 使用） */
    OPENAI_RESPONSES("openai_responses", 5, "OpenAI Responses API");

    /** 代码标识 */
    @JsonValue
    private final String key;
    /** 数值编码 */
    private final Integer value;
    /** 中文描述 */
    private final String desc;

    @JsonCreator
    public static Platform from(String value) {
        if (value == null) return null;
        for (Platform p : values()) {
            if (p.key.equalsIgnoreCase(value) || p.name().equalsIgnoreCase(value)) {
                return p;
            }
        }
        throw new IllegalArgumentException("Unknown platform: " + value);
    }
}
