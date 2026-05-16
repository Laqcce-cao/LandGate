package com.landgate.trigger.gateway;

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
public class GeminiUsageParser {

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * 从 Gemini 响应体解析用量（同时支持流式和非流式）。
     */
    public UsageTokens parse(String responseBody) {
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
}
