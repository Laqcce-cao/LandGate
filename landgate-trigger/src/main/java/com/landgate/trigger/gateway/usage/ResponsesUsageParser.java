package com.landgate.trigger.gateway.usage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.landgate.domain.billing.model.valobj.UsageTokens;
import com.landgate.types.gateway.OpenAiResponsesJsonPolicy;
import com.landgate.types.gateway.OpenAiResponsesSsePolicy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * OpenAI Responses API 用量解析器 —— 从 Responses API 响应中提取 Token 用量。
 * <p>
 * 非流式响应：{@code {"usage": {"input_tokens": N, "output_tokens": N, "input_tokens_details": {"cached_tokens": N}, ...}}}
 * <p>
 * 流式（SSE）响应：用量出现在 Responses 终止事件中：
 * {@code response.completed}、{@code response.done}、{@code response.incomplete} 或 {@code response.failed}。
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
            JsonNode usage = root.path(OpenAiResponsesJsonPolicy.FIELD_USAGE);
            if (usage.isMissingNode() || usage.isNull()) {
                return new UsageTokens();
            }
            // OpenAI Responses API: input_tokens 包含缓存部分，需减去避免重复计费
            int rawInputTokens = usage.path(OpenAiResponsesJsonPolicy.FIELD_INPUT_TOKENS).asInt();
            int cachedTokens = usage.path(OpenAiResponsesJsonPolicy.FIELD_INPUT_TOKENS_DETAILS)
                    .path(OpenAiResponsesJsonPolicy.FIELD_CACHED_TOKENS).asInt();
            return UsageTokens.builder()
                    .inputTokens(Math.max(0, rawInputTokens - cachedTokens))
                    .outputTokens(usage.path(OpenAiResponsesJsonPolicy.FIELD_OUTPUT_TOKENS).asInt())
                    .cacheReadTokens(cachedTokens)
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
            String type = root.path(OpenAiResponsesJsonPolicy.FIELD_TYPE).asText();
            if (!OpenAiResponsesSsePolicy.isTerminalEvent(type)) {
                return null;
            }
            JsonNode usage = root.path(OpenAiResponsesJsonPolicy.FIELD_RESPONSE)
                    .path(OpenAiResponsesJsonPolicy.FIELD_USAGE);
            if ((usage.isMissingNode() || usage.isNull())
                    && root.has(OpenAiResponsesJsonPolicy.FIELD_USAGE)) {
                usage = root.path(OpenAiResponsesJsonPolicy.FIELD_USAGE);
            }
            if (usage.isMissingNode() || usage.isNull()) {
                return null;
            }
            // OpenAI Responses API: input_tokens 包含缓存部分，需减去避免重复计费
            int rawInputTokens = usage.path(OpenAiResponsesJsonPolicy.FIELD_INPUT_TOKENS).asInt();
            int cachedTokens = usage.path(OpenAiResponsesJsonPolicy.FIELD_INPUT_TOKENS_DETAILS)
                    .path(OpenAiResponsesJsonPolicy.FIELD_CACHED_TOKENS).asInt();
            return UsageTokens.builder()
                    .inputTokens(Math.max(0, rawInputTokens - cachedTokens))
                    .outputTokens(usage.path(OpenAiResponsesJsonPolicy.FIELD_OUTPUT_TOKENS).asInt())
                    .cacheReadTokens(cachedTokens)
                    .build();
        } catch (Exception e) {
            log.debug("Failed to parse Responses SSE line", e);
            return null;
        }
    }

    @Override
    public boolean isStreamDone(String sseLine) {
        if (sseLine == null || sseLine.isBlank()) {
            return false;
        }
        String payload = OpenAiResponsesSsePolicy.extractDataPayload(sseLine);
        if (OpenAiResponsesSsePolicy.isDoneSentinel(payload)) {
            return true;
        }
        if (payload == null) {
            return false;
        }
        try {
            JsonNode root = JSON.readTree(payload);
            String type = root.path(OpenAiResponsesJsonPolicy.FIELD_TYPE).asText();
            return OpenAiResponsesSsePolicy.isTerminalEvent(type);
        } catch (Exception e) {
            log.debug("Failed to parse Responses SSE done line", e);
            return false;
        }
    }
}
