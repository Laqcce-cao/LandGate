package com.landgate.infrastructure.dao.po;

import lombok.*;

import java.math.BigDecimal;

/**
 * 模型价格持久化对象 —— 对应 <code>model_prices</code> 表。
 * <p>
 * 支持全局价格（group_id IS NULL）和分组覆盖价格（group_id = 分组ID）。
 * 查询时优先匹配分组覆盖，回退全局默认。
 * 支持软删除。
 */
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class ModelPricePO extends BasePO {

    /** 主键，自增 */
    private Long id;

    /** 模型标识（如 gpt-4o, claude-sonnet-4-20250514） */
    private String model;

    /** 所属 AI 平台（数据库存储为字符串，映射到实体时转为 Platform 枚举） */
    private String platform;

    /** 输入 token 单价（USD） */
    @Builder.Default
    private BigDecimal inputPrice = BigDecimal.ZERO;

    /** 输出 token 单价（USD） */
    @Builder.Default
    private BigDecimal outputPrice = BigDecimal.ZERO;

    /** 缓存写入 token 单价（Anthropic，USD） */
    @Builder.Default
    private BigDecimal cacheWritePrice = BigDecimal.ZERO;

    /** 缓存读取 token 单价（Anthropic，USD） */
    @Builder.Default
    private BigDecimal cacheReadPrice = BigDecimal.ZERO;

    /** 分组 ID（NULL = 全局默认价格） */
    private Long groupId;

    /** 是否启用 */
    @Builder.Default
    private Boolean enabled = true;

    /** 备注 */
    private String notes;
}
