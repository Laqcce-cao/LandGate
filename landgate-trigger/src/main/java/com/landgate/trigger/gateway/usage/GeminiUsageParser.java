package com.landgate.trigger.gateway.usage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.landgate.domain.billing.model.valobj.UsageTokens;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Gemini 用量解析器 —— 从 Gemini API 响应中提取 Token 用量。
 * <p>
 * 非流式响应格式：
 * <pre>{@code {"candidates": [...], "usageMetadata": {"promptTokenCount": N, "candidatesTokenCount": N}}}</pre>
 * 流式使用换行分隔 JSON，用量出现在最后一个 chunk。
 */
@Slf4j
@Component
public class GeminiUsageParser implements IUsageParser {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    public UsageTokens parseNonStreaming(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return new UsageTokens();
        }
        try {
            JsonNode root = JSON.readTree(responseBody);
            JsonNode metadata = root.path("usageMetadata");
            if (metadata.isMissingNode() || metadata.isNull()) {
                return new UsageTokens();
            }
            return UsageTokens.builder()
                    .inputTokens(metadata.path("promptTokenCount").asInt())
                    .outputTokens(metadata.path("candidatesTokenCount").asInt())
                    .build();
        } catch (Exception e) {
            log.debug("Failed to parse Gemini usage", e);
            return new UsageTokens();
        }
    }

    @Override
    public UsageTokens parseSSELine(String sseData) {
        // Gemini 流式暂不支持，返回 null
        return null;
    }

    @Override
    public boolean isStreamDone(String sseLine) {
        // Gemini 流式暂不支持
        return false;
    }
}
