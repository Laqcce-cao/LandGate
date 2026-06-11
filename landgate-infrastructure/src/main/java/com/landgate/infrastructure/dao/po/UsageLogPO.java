package com.landgate.infrastructure.dao.po;

import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 用量日志持久化对象 —— 对应 <code>usage_logs</code> 表。
 * <p>
 * 记录每次 API 调用的详细用量数据（token 消耗、费用、延迟等）。
 * 核心计费数据，不继承 BasePO（独立管理 createdAt，无软删除）。
 */
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class UsageLogPO {

    /** 主键，自增 */
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

    /** 请求 ID */
    private String requestId;

    /** 实际使用的模型 */
    private String model;

    /** 所属协议平台（ANTHROPIC/OPENAI/GEMINI） */
    private String platform;

    /** 用户请求的模型 */
    private String requestedModel;

    /** 上游实际调用的模型 */
    private String upstreamModel;

    /** 计费模式（token/per_request/image） */
    @Builder.Default
    private String billingMode = "token";

    /** 输入 token 数 */
    @Builder.Default
    private Integer inputTokens = 0;

    /** 输出 token 数 */
    @Builder.Default
    private Integer outputTokens = 0;

    /** 缓存创建 token 数 */
    @Builder.Default
    private Integer cacheCreationTokens = 0;

    /** 缓存读取 token 数 */
    @Builder.Default
    private Integer cacheReadTokens = 0;

    /** 5 分钟有效期缓存写入 token 数 */
    @Builder.Default
    private Integer cacheCreation5mTokens = 0;

    /** 1 小时有效期缓存写入 token 数 */
    @Builder.Default
    private Integer cacheCreation1hTokens = 0;

    /** 输入费用（USD） */
    @Builder.Default
    private BigDecimal inputCost = BigDecimal.ZERO;

    /** 输出费用（USD） */
    @Builder.Default
    private BigDecimal outputCost = BigDecimal.ZERO;

    /** 缓存创建费用（USD） */
    @Builder.Default
    private BigDecimal cacheCreationCost = BigDecimal.ZERO;

    /** 缓存读取费用（USD） */
    @Builder.Default
    private BigDecimal cacheReadCost = BigDecimal.ZERO;

    /** 5 分钟缓存写入费用（USD） */
    @Builder.Default
    private BigDecimal cacheCreation5mCost = BigDecimal.ZERO;

    /** 1 小时缓存写入费用（USD） */
    @Builder.Default
    private BigDecimal cacheCreation1hCost = BigDecimal.ZERO;

    /** 总费用（分组倍率后，USD） */
    @Builder.Default
    private BigDecimal totalCost = BigDecimal.ZERO;

    /** 实际成本（分组倍率前，USD） */
    @Builder.Default
    private BigDecimal actualCost = BigDecimal.ZERO;

    /** 分组倍率 */
    @Builder.Default
    private BigDecimal rateMultiplier = BigDecimal.ONE;

    /** 是否流式请求 */
    @Builder.Default
    private Boolean stream = false;

    /** 请求耗时（毫秒） */
    private Integer durationMs;

    /** 首 token 延迟（毫秒） */
    private Integer firstTokenMs;

    /** 客户端 User-Agent */
    private String userAgent;

    /** 客户端 IP */
    private String ipAddress;

    /** 图片生成数量 */
    @Builder.Default
    private Integer imageCount = 0;

    /** 图片尺寸等级（1K / 2K / 4K） */
    private String imageSize;

    /** 计费状态：PENDING / SETTLING / DEDUCTED / FAILED */
    @Builder.Default
    private String billingStatus = "PENDING";

    /** 扣费失败原因 */
    private String billingError;

    /** 流式响应期间客户端是否断开 */
    @Builder.Default
    private Boolean clientDisconnected = false;

    /** 创建时间 */
    private Instant createdAt;
}
