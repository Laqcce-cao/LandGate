package com.landgate.domain.billing.model.valobj;

import lombok.*;

/**
 * Anthropic Claude 协议的用量统计值对象。
 * <p>
 * 解析 Claude API 响应中的 usage 信息，包含输入/输出 token 和缓存写入/读取量。
 * 5m/1h 缓存标记用于区分缓存有效期。
 */
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
public class ClaudeUsageVO {

    /** 输入 Token 数 */
    @Builder.Default private int inputTokens = 0;
    /** 输出 Token 数 */
    @Builder.Default private int outputTokens = 0;
    /** 缓存写入 Token 数 */
    @Builder.Default private int cacheCreationTokens = 0;
    /** 缓存读取 Token 数 */
    @Builder.Default private int cacheReadTokens = 0;
    /** 5 分钟有效期缓存写入 Token 数 */
    @Builder.Default private int cacheCreation5mTokens = 0;
    /** 1 小时有效期缓存写入 Token 数 */
    @Builder.Default private int cacheCreation1hTokens = 0;

    public boolean hasUsage() {
        return inputTokens > 0 || outputTokens > 0 || cacheCreationTokens > 0 || cacheReadTokens > 0;
    }

    public void mergeNonZero(ClaudeUsageVO other) {
        if (other.inputTokens > 0) this.inputTokens = other.inputTokens;
        if (other.outputTokens > 0) this.outputTokens = other.outputTokens;
        if (other.cacheCreationTokens > 0) this.cacheCreationTokens = other.cacheCreationTokens;
        if (other.cacheReadTokens > 0) this.cacheReadTokens = other.cacheReadTokens;
        if (other.cacheCreation5mTokens > 0) this.cacheCreation5mTokens = other.cacheCreation5mTokens;
        if (other.cacheCreation1hTokens > 0) this.cacheCreation1hTokens = other.cacheCreation1hTokens;
    }
}
