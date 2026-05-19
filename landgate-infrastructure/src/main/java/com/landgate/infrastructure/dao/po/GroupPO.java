package com.landgate.infrastructure.dao.po;

import com.landgate.types.enums.Platform;
import com.landgate.types.enums.Status;
import com.landgate.types.enums.SubscriptionType;
import lombok.*;

import java.math.BigDecimal;

/**
 * 分组持久化对象 —— 对应 <code>groups</code> 表。
 * <p>
 * 分组是核心业务实体，定义计费规则、模型路由、用量限制等配置。
 * 每个 API Key 归属一个分组，按分组规则计价和调度。
 * 支持软删除。
 */
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class GroupPO extends BasePO {

    /** 主键，自增 */
    private Long id;

    /** 分组名称 */
    private String name;

    /** 分组描述 */
    private String description;

    /** 价格倍率 */
    @Builder.Default
    private BigDecimal rateMultiplier = BigDecimal.ONE;

    /** 是否独占分组 */
    @Builder.Default
    private Boolean isExclusive = false;

    /** 分组状态 */
    @Builder.Default
    private Status status = Status.ACTIVE;

    /** 所属 AI 平台 */
    @Builder.Default
    private Platform platform = Platform.ANTHROPIC;

    /** 订阅类型 */
    @Builder.Default
    private SubscriptionType subscriptionType = SubscriptionType.STANDARD;

    /** 每日消费上限（USD） */
    private BigDecimal dailyLimitUsd;

    /** 每周消费上限（USD） */
    private BigDecimal weeklyLimitUsd;

    /** 每月消费上限（USD） */
    private BigDecimal monthlyLimitUsd;

    /** 默认有效期（天） */
    @Builder.Default
    private Integer defaultValidityDays = 30;

    /** 是否允许图片生成 */
    @Builder.Default
    private Boolean allowImageGeneration = false;

    /** 图片计费是否独立 */
    @Builder.Default
    private Boolean imageRateIndependent = false;

    /** 图片价格倍率 */
    @Builder.Default
    private BigDecimal imageRateMultiplier = BigDecimal.ONE;

    /** 图片价格（1K 分辨率） */
    private BigDecimal imagePrice1k;

    /** 图片价格（2K 分辨率） */
    private BigDecimal imagePrice2k;

    /** 图片价格（4K 分辨率） */
    private BigDecimal imagePrice4k;

    /** 是否仅限 Claude Code 使用 */
    @Builder.Default
    private Boolean claudeCodeOnly = false;

    /** 降级分组 ID */
    private Long fallbackGroupId;

    /** 无效请求降级分组 ID */
    private Long fallbackGroupIdOnInvalidRequest;

    /** 模型路由配置（JSON） */
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

    /** 排序序号 */
    @Builder.Default
    private Integer sortOrder = 0;

    /** 是否允许 Messages 分发 */
    @Builder.Default
    private Boolean allowMessagesDispatch = false;

    /** 是否要求仅 OAuth 账号 */
    @Builder.Default
    private Boolean requireOauthOnly = false;

    /** 是否要求隐私设置 */
    @Builder.Default
    private Boolean requirePrivacySet = false;

    /** 默认映射模型名 */
    @Builder.Default
    private String defaultMappedModel = "";

    /** Messages 分发模型配置（JSON） */
    @Builder.Default
    private String messagesDispatchModelConfig = "{}";

    /** 每分钟请求数上限（0 = 不限制） */
    @Builder.Default
    private Integer rpmLimit = 0;

    /** 排除模型列表（JSON 数组），例如 ["claude-opus-4","gemini-pro"] */
    private String excludedModels;

    public boolean isActive() { return Status.ACTIVE == status; }
    public boolean isExclusiveGroup() { return isExclusive != null && isExclusive; }
    public boolean hasIndependentImageRate() { return imageRateIndependent != null && imageRateIndependent; }
    public boolean hasModelRoutingEnabled() { return modelRoutingEnabled != null && modelRoutingEnabled; }
}
