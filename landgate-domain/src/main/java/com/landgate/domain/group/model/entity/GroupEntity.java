package com.landgate.domain.group.model.entity;

import com.landgate.types.enums.Platform;
import com.landgate.types.enums.Status;
import com.landgate.types.enums.SubscriptionType;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 分组实体 —— 对应数据库 groups 表。
 * <p>
 * 分组是用户与上游资源之间的隔离层：管理员将上游账号绑定到分组，
 * 再将分组授权给用户。支持独占模式、费率倍数、用量限额、模型路由、
 * 图片生成控制和 Claude Code 专用等高级配置。
 */
@Getter @Setter
@Builder
@NoArgsConstructor @AllArgsConstructor
public class GroupEntity {

    private Long id;

    /** 分组名称 */
    private String name;

    /** 分组描述 */
    private String description;

    /** 费率倍数，默认 1.0 */
    @Builder.Default
    private BigDecimal rateMultiplier = BigDecimal.ONE;

    /** 是否独占分组 */
    @Builder.Default
    private Boolean isExclusive = false;

    /** 分组状态 */
    @Builder.Default
    private Status status = Status.ACTIVE;

    /** 默认平台 */
    @Builder.Default
    private Platform platform = Platform.ANTHROPIC;

    /** 订阅类型 */
    @Builder.Default
    private SubscriptionType subscriptionType = SubscriptionType.STANDARD;

    /** 每日消费限额（USD） */
    private BigDecimal dailyLimitUsd;
    /** 每周消费限额（USD） */
    private BigDecimal weeklyLimitUsd;
    /** 每月消费限额（USD） */
    private BigDecimal monthlyLimitUsd;

    /** 默认有效期天数 */
    @Builder.Default
    private Integer defaultValidityDays = 30;

    /** 是否允许图片生成 */
    @Builder.Default
    private Boolean allowImageGeneration = false;

    /** 图片费率是否独立计算 */
    @Builder.Default
    private Boolean imageRateIndependent = false;

    /** 图片费率倍数 */
    @Builder.Default
    private BigDecimal imageRateMultiplier = BigDecimal.ONE;

    private BigDecimal imagePrice1k;
    private BigDecimal imagePrice2k;
    private BigDecimal imagePrice4k;

    /** 是否仅限 Claude Code 使用 */
    @Builder.Default
    private Boolean claudeCodeOnly = false;

    /** 降级分组 ID（当前分组不可用时兜底） */
    private Long fallbackGroupId;

    /** 无效请求降级分组 ID */
    private Long fallbackGroupIdOnInvalidRequest;

    /** 模型路由规则（JSON 格式） */
    @Builder.Default
    private String modelRouting = "{}";

    /** 是否启用模型路由 */
    @Builder.Default
    private Boolean modelRoutingEnabled = false;

    /** 是否注入 MCP XML */
    @Builder.Default
    private Boolean mcpXmlInject = true;

    /** 支持的模型范围（JSON 数组） */
    @Builder.Default
    private String supportedModelScopes = "[\"claude\", \"gemini_text\", \"gemini_image\"]";

    /** 排序权重 */
    @Builder.Default
    private Integer sortOrder = 0;

    /** 是否允许 messages 分发 */
    @Builder.Default
    private Boolean allowMessagesDispatch = false;

    /** 是否要求 OAuth 登录 */
    @Builder.Default
    private Boolean requireOauthOnly = false;

    /** 是否要求隐私设置 */
    @Builder.Default
    private Boolean requirePrivacySet = false;

    /** 默认映射模型 */
    @Builder.Default
    private String defaultMappedModel = "";

    /** messages 分发模型配置（JSON） */
    @Builder.Default
    private String messagesDispatchModelConfig = "{}";

    /** 每分钟请求数限制 */
    @Builder.Default
    private Integer rpmLimit = 0;

    private Instant createdAt;
    private Instant updatedAt;
    private Instant deletedAt;

    public boolean isActive() { return Status.ACTIVE == status; }
    public boolean isExclusiveGroup() { return isExclusive != null && isExclusive; }
    public boolean hasIndependentImageRate() { return imageRateIndependent != null && imageRateIndependent; }
    public boolean hasModelRoutingEnabled() { return modelRoutingEnabled != null && modelRoutingEnabled; }
}
