package com.landgate.trigger.gateway.limit;

import java.time.Instant;

/**
 * 上游 API Rate Limit 快照 —— 从响应头解析的归一化用量数据。
 * <p>
 * 每次成功的上游请求后捕获，存储在 AccountEntity 的 session_window_* 字段中。
 */
public record RateLimitSnapshot(
        /** 窗口起始（捕获时刻） */
        Instant windowStart,
        /** 窗口结束（最远的 reset 时间） */
        Instant windowEnd,
        /** JSON: {"tokens":{"limit":N,"remaining":N,"reset":"ISO"},"requests":{...}} */
        String statusJson
) {
    public boolean hasData() {
        return statusJson != null && !statusJson.isEmpty();
    }
}
