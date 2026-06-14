package com.landgate.trigger.gateway.usage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.landgate.domain.billing.model.valobj.ClaudeUsageVO;
import com.landgate.domain.billing.model.valobj.UsageTokens;
import com.landgate.types.gateway.AnthropicMessagesBodyPolicy;
import com.landgate.types.gateway.AnthropicMessagesSsePolicy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Claude/Anthropic 用量解析器 —— 从 Anthropic API 响应中提取 Token 用量。
 * <p>
 * 支持流式 SSE 事件行解析（message_start / message_delta）和非流式完整响应解析。
 * <p>
 * <b>字段语义</b>：Anthropic API 的 {@code input_tokens} 不包含缓存读写 token，
 * 三者是<b>互斥</b>的独立类别 —— 总输入上下文 = inputTokens + cacheReadTokens + cacheCreationTokens。
 * 缓存读取 token 按 cache_read_price 计费（约为 input_price 的 10%），缓存写入 token 额外计费。
 */
@Slf4j
@Component
public class AnthropicUsageParser implements IUsageParser {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    public UsageTokens parseSSELine(String sseEventLine) {
        if (sseEventLine == null || sseEventLine.isBlank()) return null;
        try {
            JsonNode root = JSON.readTree(sseEventLine);
            String type = root.has(AnthropicMessagesBodyPolicy.FIELD_TYPE)
                    ? root.get(AnthropicMessagesBodyPolicy.FIELD_TYPE).asText()
                    : null;
            if (type == null) return null;
            ClaudeUsageVO claudeUsage = switch (type) {
                case AnthropicMessagesSsePolicy.EVENT_MESSAGE_START -> parseMessageStart(root);
                case AnthropicMessagesSsePolicy.EVENT_MESSAGE_DELTA -> parseMessageDelta(root);
                default -> null;
            };
            return claudeUsage != null ? UsageTokens.fromClaude(claudeUsage) : null;
        } catch (Exception e) {
            log.debug("Failed to parse SSE line: {}", sseEventLine.substring(0, Math.min(200, sseEventLine.length())));
            return null;
        }
    }

    private ClaudeUsageVO parseMessageStart(JsonNode root) {
        JsonNode message = root.path(AnthropicMessagesBodyPolicy.FIELD_MESSAGE);
        JsonNode usage = message.path(AnthropicMessagesBodyPolicy.FIELD_USAGE);
        int inputTokens = usage.path(AnthropicMessagesBodyPolicy.FIELD_INPUT_TOKENS).asInt();
        int cacheCreationTokens = usage.path(AnthropicMessagesBodyPolicy.FIELD_CACHE_CREATION_INPUT_TOKENS).asInt();
        int cacheReadTokens = usage.path(AnthropicMessagesBodyPolicy.FIELD_CACHE_READ_INPUT_TOKENS).asInt();
        if (cacheReadTokens == 0) {
            cacheReadTokens = usage.path(AnthropicMessagesBodyPolicy.FIELD_CACHED_TOKENS).asInt();
        }
        JsonNode cacheCreation = usage.path(AnthropicMessagesBodyPolicy.FIELD_CACHE_CREATION);
        int cacheCreation5m = cacheCreation.path(AnthropicMessagesBodyPolicy.FIELD_EPHEMERAL_5M_INPUT_TOKENS)
                .asInt();
        int cacheCreation1h = cacheCreation.path(AnthropicMessagesBodyPolicy.FIELD_EPHEMERAL_1H_INPUT_TOKENS)
                .asInt();
        if (cacheCreationTokens == 0) {
            cacheCreationTokens = cacheCreation5m + cacheCreation1h;
        }
        return ClaudeUsageVO.builder()
                .inputTokens(inputTokens).cacheCreationTokens(cacheCreationTokens)
                .cacheReadTokens(cacheReadTokens).cacheCreation5mTokens(cacheCreation5m)
                .cacheCreation1hTokens(cacheCreation1h).build();
    }

    private ClaudeUsageVO parseMessageDelta(JsonNode root) {
        JsonNode usage = root.path(AnthropicMessagesBodyPolicy.FIELD_USAGE);
        int outputTokens = usage.path(AnthropicMessagesBodyPolicy.FIELD_OUTPUT_TOKENS).asInt();
        int inputTokens = usage.path(AnthropicMessagesBodyPolicy.FIELD_INPUT_TOKENS).asInt();
        int cacheCreationTokens = usage.path(AnthropicMessagesBodyPolicy.FIELD_CACHE_CREATION_INPUT_TOKENS).asInt();
        int cacheReadTokens = usage.path(AnthropicMessagesBodyPolicy.FIELD_CACHE_READ_INPUT_TOKENS).asInt();
        if (cacheReadTokens == 0) {
            cacheReadTokens = usage.path(AnthropicMessagesBodyPolicy.FIELD_CACHED_TOKENS).asInt();
        }
        JsonNode cacheCreation = usage.path(AnthropicMessagesBodyPolicy.FIELD_CACHE_CREATION);
        int cacheCreation5m = cacheCreation.path(AnthropicMessagesBodyPolicy.FIELD_EPHEMERAL_5M_INPUT_TOKENS)
                .asInt();
        int cacheCreation1h = cacheCreation.path(AnthropicMessagesBodyPolicy.FIELD_EPHEMERAL_1H_INPUT_TOKENS)
                .asInt();
        if (cacheCreationTokens == 0) {
            cacheCreationTokens = cacheCreation5m + cacheCreation1h;
        }
        return ClaudeUsageVO.builder()
                .outputTokens(outputTokens).inputTokens(inputTokens)
                .cacheCreationTokens(cacheCreationTokens).cacheReadTokens(cacheReadTokens)
                .cacheCreation5mTokens(cacheCreation5m).cacheCreation1hTokens(cacheCreation1h).build();
    }

    @Override
    public UsageTokens parseNonStreaming(String responseBody) {
        try {
            JsonNode root = JSON.readTree(responseBody);
            JsonNode usage = root.path(AnthropicMessagesBodyPolicy.FIELD_USAGE);
            JsonNode cacheCreation = usage.path(AnthropicMessagesBodyPolicy.FIELD_CACHE_CREATION);
            int cacheCreation5m = cacheCreation.path(AnthropicMessagesBodyPolicy.FIELD_EPHEMERAL_5M_INPUT_TOKENS)
                    .asInt();
            int cacheCreation1h = cacheCreation.path(AnthropicMessagesBodyPolicy.FIELD_EPHEMERAL_1H_INPUT_TOKENS)
                    .asInt();
            int cacheCreationTokens = usage.path(AnthropicMessagesBodyPolicy.FIELD_CACHE_CREATION_INPUT_TOKENS)
                    .asInt();
            if (cacheCreationTokens == 0) {
                cacheCreationTokens = cacheCreation5m + cacheCreation1h;
            }
            int cacheReadTokens = usage.path(AnthropicMessagesBodyPolicy.FIELD_CACHE_READ_INPUT_TOKENS).asInt();
            if (cacheReadTokens == 0) {
                cacheReadTokens = usage.path(AnthropicMessagesBodyPolicy.FIELD_CACHED_TOKENS).asInt();
            }
            return UsageTokens.builder()
                    .inputTokens(usage.path(AnthropicMessagesBodyPolicy.FIELD_INPUT_TOKENS).asInt())
                    .outputTokens(usage.path(AnthropicMessagesBodyPolicy.FIELD_OUTPUT_TOKENS).asInt())
                    .cacheCreationTokens(cacheCreationTokens)
                    .cacheReadTokens(cacheReadTokens)
                    .cacheCreation5mTokens(cacheCreation5m)
                    .cacheCreation1hTokens(cacheCreation1h)
                    .build();
        } catch (Exception e) {
            log.warn("Failed to parse usage from response body");
            return new UsageTokens();
        }
    }

    @Override
    public boolean isStreamDone(String sseLine) {
        String payload = AnthropicMessagesSsePolicy.extractDataPayload(sseLine);
        if (AnthropicMessagesSsePolicy.isDoneSentinel(payload)) {
            return true;
        }
        if (payload == null) {
            return false;
        }
        try {
            JsonNode root = JSON.readTree(payload);
            return AnthropicMessagesSsePolicy.EVENT_MESSAGE_STOP.equals(
                    root.path(AnthropicMessagesBodyPolicy.FIELD_TYPE).asText());
        } catch (Exception e) {
            log.debug("Failed to parse Anthropic SSE done line", e);
            return false;
        }
    }
}
