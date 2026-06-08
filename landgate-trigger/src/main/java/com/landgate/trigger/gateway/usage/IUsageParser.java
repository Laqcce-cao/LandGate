package com.landgate.trigger.gateway.usage;

import com.landgate.domain.billing.model.valobj.UsageTokens;

/**
 * 用量解析器接口 —— 从上游 AI 提供商响应中提取 Token 用量。
 */
public interface IUsageParser {

    UsageTokens parseSSELine(String sseData);

    UsageTokens parseNonStreaming(String responseBody);

    boolean isStreamDone(String sseLine);
}
