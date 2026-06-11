package com.landgate.trigger.gateway.usage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.landgate.domain.billing.model.valobj.UsageTokens;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * OpenAI 用量解析器 —— 从 OpenAI API 响应中提取 Token 用量。
 * <p>
 * 非流式响应：{@code {"usage": {"prompt_tokens": N, "completion_tokens": N, "prompt_tokens_details": {"cached_tokens": N}}}}
 * <p>
 * 流式（SSE）响应：用量仅出现在最后一个 chunk 中：
 * {@code data: {"choices": [...], "usage": {...}}}
 */
@Slf4j
@Component
public class OpenAiUsageParser implements IUsageParser {

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
            // OpenAI API: prompt_tokens 包含缓存部分，需减去避免重复计费
            int rawPromptTokens = usage.path("prompt_tokens").asInt();
            int cachedTokens = usage.path("prompt_tokens_details").path("cached_tokens").asInt();
            return UsageTokens.builder()
                    .inputTokens(Math.max(0, rawPromptTokens - cachedTokens))
                    .outputTokens(usage.path("completion_tokens").asInt())
                    .cacheReadTokens(cachedTokens)
                    .build();
        } catch (Exception e) {
            log.debug("Failed to parse OpenAI non-streaming usage", e);
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
            JsonNode usage = root.path("usage");
            if (usage.isMissingNode() || usage.isNull()) {
                return null;
            }
            // OpenAI API: prompt_tokens 包含缓存部分，需减去避免重复计费
            int rawPromptTokens = usage.path("prompt_tokens").asInt();
            int cachedTokens = usage.path("prompt_tokens_details").path("cached_tokens").asInt();
            return UsageTokens.builder()
                    .inputTokens(Math.max(0, rawPromptTokens - cachedTokens))
                    .outputTokens(usage.path("completion_tokens").asInt())
                    .cacheReadTokens(cachedTokens)
                    .build();
        } catch (Exception e) {
            log.debug("Failed to parse OpenAI SSE line", e);
            return null;
        }
    }

    @Override
    public boolean isStreamDone(String sseLine) {
        return "data: [DONE]".equals(sseLine);
    }
}
