package com.landgate.domain.billing.model.entity;

import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 用量日志实体 —— 对应数据库 usage_logs 表。
 * <p>
 * 记录每次 API 调用的详细计费信息：请求模型、Token 用量、各项费用、
 * 倍率、流式/非流式、耗时等。是计费系统的核心数据实体。
 */
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
public class UsageLogEntity {

    private Long id;

    /** 用户 ID */
    private Long userId;
    /** API Key ID */
    private Long apiKeyId;
    /** 上游账号 ID */
    private Long accountId;
    /** 分组 ID */
    private Long groupId;
    /** 订阅 ID */
    private Long subscriptionId;

    /** 请求唯一标识 */
    private String requestId;
    /** 计费模型名称 */
    private String model;
    /** 所属平台 */
    private String platform;
    /** 客户端请求的模型名 */
    private String requestedModel;
    /** 实际使用的上游模型名 */
    private String upstreamModel;

    /** 计费模式（token / request 等） */
    @Builder.Default
    private String billingMode = "token";

    /** 输入 Token 数 */
    @Builder.Default
    private Integer inputTokens = 0;
    /** 输出 Token 数 */
    @Builder.Default
    private Integer outputTokens = 0;
    /** 缓存写入 Token 数 */
    @Builder.Default
    private Integer cacheCreationTokens = 0;
    /** 缓存读取 Token 数 */
    @Builder.Default
    private Integer cacheReadTokens = 0;

    @Builder.Default
    private BigDecimal inputCost = BigDecimal.ZERO;
    @Builder.Default
    private BigDecimal outputCost = BigDecimal.ZERO;
    @Builder.Default
    private BigDecimal cacheCreationCost = BigDecimal.ZERO;
    @Builder.Default
    private BigDecimal cacheReadCost = BigDecimal.ZERO;
    @Builder.Default
    private BigDecimal totalCost = BigDecimal.ZERO;
    @Builder.Default
    private BigDecimal actualCost = BigDecimal.ZERO;

    @Builder.Default
    private BigDecimal rateMultiplier = BigDecimal.ONE;
    private BigDecimal accountRateMultiplier;

    @Builder.Default
    private Boolean stream = false;
    private Integer durationMs;
    private Integer firstTokenMs;

    private String userAgent;
    private String ipAddress;

    private Instant createdAt;
}
