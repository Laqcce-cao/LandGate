package com.landgate.domain.billing.service;

import com.landgate.domain.billing.model.valobj.LiteLLMPrice;

/**
 * LiteLLM 价格查询桥接接口 —— 由 trigger 模块的 {@code LiteLLMSyncService} 实现。
 * <p>
 * 领域层通过此接口使用 LiteLLM 远程价格缓存，不直接依赖触发层。
 */
public interface LiteLLMSyncServiceBridge {

    /**
     * 按模型名查询 LiteLLM 价格（含模糊匹配）。
     *
     * @param model 模型名称
     * @return 匹配的 LiteLLM 价格，不存在返回 null
     */
    LiteLLMPrice findPrice(String model);

    /**
     * 缓存是否已初始化（至少加载过一次数据）。
     */
    boolean isInitialized();
}
