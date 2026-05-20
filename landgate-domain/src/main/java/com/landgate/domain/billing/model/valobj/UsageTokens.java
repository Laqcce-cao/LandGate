package com.landgate.domain.billing.model.valobj;

import lombok.*;

/**
 * 协议无关的通用 Token 用量值对象 —— 替代 {@link ClaudeUsageVO} 作为统一的用量容器。
 * <p>
 * 支持 Anthropic、OpenAI、Gemini 三个平台的用量统计。
 * 提供从 Claude 协议转换的工厂方法。
 * <p>
 * <b>字段语义</b>：Anthropic API 的 {@code input_tokens} 不包含缓存读写 token，
 * 三者是<b>互斥</b>的独立类别 —— 总输入上下文 = inputTokens + cacheReadTokens + cacheCreationTokens。
 * 缓存读取 token 按 cache_read_price 计费（约为 input_price 的 10%），缓存写入 token 额外计费。
 */
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
public class UsageTokens {

    /** 输入 Token 数（不包含缓存命中 + 缓存写入部分） */
    @Builder.Default private int inputTokens = 0;
    /** 输出 Token 数 */
    @Builder.Default private int outputTokens = 0;
    /** 缓存写入 Token 数 */
    @Builder.Default private int cacheCreationTokens = 0;
    /** 缓存读取 Token 数 */
    @Builder.Default private int cacheReadTokens = 0;
    /** 5 分钟有效期缓存写入 Token 数（Anthropic ephemeral_5m） */
    @Builder.Default private int cacheCreation5mTokens = 0;
    /** 1 小时有效期缓存写入 Token 数（Anthropic ephemeral_1h） */
    @Builder.Default private int cacheCreation1hTokens = 0;

    public boolean hasUsage() {
        return inputTokens > 0 || outputTokens > 0
                || cacheCreationTokens > 0 || cacheReadTokens > 0
                || cacheCreation5mTokens > 0 || cacheCreation1hTokens > 0;
    }

    public static UsageTokens fromClaude(ClaudeUsageVO claude) {
        return UsageTokens.builder()
                .inputTokens(claude.getInputTokens())
                .outputTokens(claude.getOutputTokens())
                .cacheCreationTokens(claude.getCacheCreationTokens())
                .cacheReadTokens(claude.getCacheReadTokens())
                .cacheCreation5mTokens(claude.getCacheCreation5mTokens())
                .cacheCreation1hTokens(claude.getCacheCreation1hTokens())
                .build();
    }

    /**
     * 合并另一个 UsageTokens 的非零值到当前对象。
     * 用于流式 SSE 响应中逐事件行累积用量。
     * <p>
     * Anthropic SSE 协议中，{@code message_start} 和 {@code message_delta}
     * 均上报累计全量 usage，因此使用<b>覆盖</b>（非累加）语义。
     */
    public void merge(UsageTokens other) {
        if (other.inputTokens > 0) this.inputTokens = other.inputTokens;
        if (other.outputTokens > 0) this.outputTokens = other.outputTokens;
        if (other.cacheCreationTokens > 0) this.cacheCreationTokens = other.cacheCreationTokens;
        if (other.cacheReadTokens > 0) this.cacheReadTokens = other.cacheReadTokens;
        if (other.cacheCreation5mTokens > 0) this.cacheCreation5mTokens = other.cacheCreation5mTokens;
        if (other.cacheCreation1hTokens > 0) this.cacheCreation1hTokens = other.cacheCreation1hTokens;
    }
}
