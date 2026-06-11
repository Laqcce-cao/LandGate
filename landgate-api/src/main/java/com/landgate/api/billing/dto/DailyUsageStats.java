package com.landgate.api.billing.dto;

import java.time.LocalDate;

/**
 * 按天聚合的用量统计 DTO —— 用于前端 Token 用量趋势图表。
 * <p>
 * 每条记录代表一个用户在某一天的所有 API 调用的 Token 总量和调用次数。
 * 由后端通过聚合查询直接返回，前端无需再对原始日志做二次聚合。
 */
public record DailyUsageStats(
        /** 统计日期 */
        LocalDate date,
        /** 该天所有调用的输入 Token 总量 */
        Long inputTokens,
        /** 该天所有调用的输出 Token 总量 */
        Long outputTokens,
        /** 该天所有调用的缓存读取 Token 总量 */
        Long cacheReadTokens,
        /** 该天的 API 调用次数 */
        Long callCount
) {}
