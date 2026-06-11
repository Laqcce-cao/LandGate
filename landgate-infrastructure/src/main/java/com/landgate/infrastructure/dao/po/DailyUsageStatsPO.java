package com.landgate.infrastructure.dao.po;

import java.time.LocalDate;

/**
 * 按天聚合的用量统计 PO —— 对应 {@code UsageLogMapper.xml} 中 {@code dailyStatsResultMap} 的结果映射。
 * <p>
 * 用于 MyBatis 从 {@code aggregateByUserAndDate} 查询中映射结果，最终在 Repository 层转换为 {@code DailyUsageStats} DTO。
 */
public class DailyUsageStatsPO {

    /** 统计日期 */
    private LocalDate date;
    /** 该天所有调用的输入 Token 总量 */
    private Long inputTokens;
    /** 该天所有调用的输出 Token 总量 */
    private Long outputTokens;
    /** 该天所有调用的缓存读取 Token 总量 */
    private Long cacheReadTokens;
    /** 该天的 API 调用次数 */
    private Long callCount;

    public LocalDate getDate() { return date; }
    public void setDate(LocalDate date) { this.date = date; }
    public Long getInputTokens() { return inputTokens; }
    public void setInputTokens(Long inputTokens) { this.inputTokens = inputTokens; }
    public Long getOutputTokens() { return outputTokens; }
    public void setOutputTokens(Long outputTokens) { this.outputTokens = outputTokens; }
    public Long getCacheReadTokens() { return cacheReadTokens; }
    public void setCacheReadTokens(Long cacheReadTokens) { this.cacheReadTokens = cacheReadTokens; }
    public Long getCallCount() { return callCount; }
    public void setCallCount(Long callCount) { this.callCount = callCount; }
}
