package com.landgate.trigger.gateway.response;

import com.landgate.domain.billing.model.valobj.UsageTokens;

/** 流式响应处理结果，包含用量和客户端断开审计标记。 */
public record GatewayResponseResult(UsageTokens usage, boolean clientDisconnected) {
}
