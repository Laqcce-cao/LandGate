package com.landgate.domain.billing.model.valobj;

import lombok.*;

/**
 * 协议无关的通用 Token 用量值对象 —— 替代 {@link ClaudeUsageVO} 作为统一的用量容器。
 * <p>
 * 支持 Anthropic、OpenAI、Gemini 三个平台的用量统计。
 * 提供从 Claude 协议转换的工厂方法。
 */
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
public class UsageTokens {

    /** 输入 Token 数 */
    @Builder.Default private int inputTokens = 0;
    /** 输出 Token 数 */
    @Builder.Default private int outputTokens = 0;
    /** 缓存写入 Token 数 */
    @Builder.Default private int cacheCreationTokens = 0;
    /** 缓存读取 Token 数 */
    @Builder.Default private int cacheReadTokens = 0;

    public boolean hasUsage() {
        return inputTokens > 0 || outputTokens > 0 || cacheCreationTokens > 0 || cacheReadTokens > 0;
    }

    public static UsageTokens fromClaude(ClaudeUsageVO claude) {
        return UsageTokens.builder()
                .inputTokens(claude.getInputTokens())
                .outputTokens(claude.getOutputTokens())
                .cacheCreationTokens(claude.getCacheCreationTokens())
                .cacheReadTokens(claude.getCacheReadTokens())
                .build();
    }
}
