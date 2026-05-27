package com.landgate.trigger.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.landgate.domain.billing.model.valobj.UsageTokens;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * OpenAI Responses API 用量解析器 —— 从 Responses API 响应中提取 Token 用量。
 * <p>
 * 非流式响应：{@code {"usage": {"input_tokens": N, "output_tokens": N, "total_tokens": N}}}
 * <p>
 * 流式（SSE）响应：用量出现在 {@code response.completed} 事件中：
 * {@code data: {"type":"response.completed","response":{"usage":{"input_tokens":N,"output_tokens":N,...}}}}
 * <p>
 * 注意：Responses API SSE 流没有 Chat Completions 的 {@code [DONE]} 标记，
 * 流结束由 {@code response.completed} 事件表示。
 */
@Slf4j
@Component
public class ResponsesUsageParser implements IUsageParser {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    public UsageTokens parseNonStreaming(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return new UsageTokens();
        }
        try {
            JsonNode root = JSON.readTree(responseBody);
            JsonNode usage = root.path("usage");
            if (usage.isMissingNode() || usage.isNull()) {
                return new UsageTokens();
            }
            return UsageTokens.builder()
                    .inputTokens(usage.path("input_tokens").asInt())
                    .outputTokens(usage.path("output_tokens").asInt())
                    .build();
        } catch (Exception e) {
            log.debug("Failed to parse Responses non-streaming usage", e);
            return new UsageTokens();
        }
    }

    @Override
    public UsageTokens parseSSELine(String sseData) {
        if (sseData == null || sseData.isBlank()) {
            return null;
        }
        try {
            JsonNode root = JSON.readTree(sseData);
            // 仅从 response.completed 事件中提取用量
            String type = root.path("type").asText();
            if (!"response.completed".equals(type)) {
                return null;
            }
            JsonNode usage = root.path("response").path("usage");
            if (usage.isMissingNode() || usage.isNull()) {
                return null;
            }
            return UsageTokens.builder()
                    .inputTokens(usage.path("input_tokens").asInt())
                    .outputTokens(usage.path("output_tokens").asInt())
                    .build();
        } catch (Exception e) {
            log.debug("Failed to parse Responses SSE line", e);
            return null;
        }
    }

    @Override
    public boolean isStreamDone(String sseLine) {
        // Responses API SSE 没有 [DONE] 标记，流结束由 response.completed 事件决定
        // 此处返回 false，由调用方通过 response.completed 判断流结束
        return false;
    }
}
