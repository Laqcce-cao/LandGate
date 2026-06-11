package com.landgate.domain.billing.model.valobj;

import java.math.BigDecimal;

/**
 * LiteLLM 远程模型价格 VO —— 从 LiteLLM model_prices_and_context_window.json 解析的单条价格记录。
 * <p>
 * 仅保留计费所需的核心字段，忽略 context_window、supports_vision 等无关信息。
 *
 * @param model       模型名（LiteLLM 原始命名）
 * @param inputPrice  输入价格（$/百万 tokens），可能为 0
 * @param outputPrice 输出价格（$/百万 tokens），可能为 0
 */
public record LiteLLMPrice(
        String model,
        BigDecimal inputPrice,
        BigDecimal outputPrice
) {

    /** 转换为此项目通用的 Price 记录（缓存读写价格归零） */
    public com.landgate.domain.billing.service.ModelPricingDomainService.Price toPrice() {
        return new com.landgate.domain.billing.service.ModelPricingDomainService.Price(
                inputPrice != null ? inputPrice : BigDecimal.ZERO,
                outputPrice != null ? outputPrice : BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, false);
    }
}
