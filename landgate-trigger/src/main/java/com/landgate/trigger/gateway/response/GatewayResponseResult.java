package com.landgate.trigger.gateway.response;

import com.landgate.domain.billing.model.valobj.UsageTokens;

/** 流式响应处理结果，包含用量、客户端断开和上游协议完整性审计标记。 */
public record GatewayResponseResult(UsageTokens usage,
                                    boolean clientDisconnected,
                                    String responseId,
                                    boolean protocolError,
                                    String protocolErrorMessage) {
    public GatewayResponseResult(UsageTokens usage, boolean clientDisconnected) {
        this(usage, clientDisconnected, "");
    }

    public GatewayResponseResult(UsageTokens usage, boolean clientDisconnected, String responseId) {
        this(usage, clientDisconnected, responseId, false, "");
    }

    public static GatewayResponseResult protocolError(UsageTokens usage,
                                                      boolean clientDisconnected,
                                                      String responseId,
                                                      String message) {
        return new GatewayResponseResult(usage, clientDisconnected, responseId, true,
                message == null ? "" : message);
    }
}
