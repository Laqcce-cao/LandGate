package com.landgate.trigger.gateway.usage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.landgate.domain.billing.model.valobj.ClaudeUsageVO;
import com.landgate.domain.billing.model.valobj.UsageTokens;
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
            String type = root.has("type") ? root.get("type").asText() : null;
            if (type == null) return null;
            ClaudeUsageVO claudeUsage = switch (type) {
                case "message_start" -> parseMessageStart(root);
                case "message_delta" -> parseMessageDelta(root);
                default -> null;
            };
            return claudeUsage != null ? UsageTokens.fromClaude(claudeUsage) : null;
        } catch (Exception e) {
            log.debug("Failed to parse SSE line: {}", sseEventLine.substring(0, Math.min(200, sseEventLine.length())));
            return null;
        }
    }

    private ClaudeUsageVO parseMessageStart(JsonNode root) {
        JsonNode message = root.path("message");
        JsonNode usage = message.path("usage");
        int inputTokens = usage.path("input_tokens").asInt();
        int cacheCreationTokens = usage.path("cache_creation_input_tokens").asInt();
        int cacheReadTokens = usage.path("cache_read_input_tokens").asInt();
        JsonNode cacheCreation = usage.path("cache_creation");
        int cacheCreation5m = cacheCreation.path("ephemeral_5m_input_tokens").asInt();
        int cacheCreation1h = cacheCreation.path("ephemeral_1h_input_tokens").asInt();
        return ClaudeUsageVO.builder()
                .inputTokens(inputTokens).cacheCreationTokens(cacheCreationTokens)
                .cacheReadTokens(cacheReadTokens).cacheCreation5mTokens(cacheCreation5m)
                .cacheCreation1hTokens(cacheCreation1h).build();
    }

    private ClaudeUsageVO parseMessageDelta(JsonNode root) {
        JsonNode usage = root.path("usage");
        int outputTokens = usage.path("output_tokens").asInt();
        int inputTokens = usage.path("input_tokens").asInt();
        int cacheCreationTokens = usage.path("cache_creation_input_tokens").asInt();
        int cacheReadTokens = usage.path("cache_read_input_tokens").asInt();
        JsonNode cacheCreation = usage.path("cache_creation");
        int cacheCreation5m = cacheCreation.path("ephemeral_5m_input_tokens").asInt();
        int cacheCreation1h = cacheCreation.path("ephemeral_1h_input_tokens").asInt();
        return ClaudeUsageVO.builder()
                .outputTokens(outputTokens).inputTokens(inputTokens)
                .cacheCreationTokens(cacheCreationTokens).cacheReadTokens(cacheReadTokens)
                .cacheCreation5mTokens(cacheCreation5m).cacheCreation1hTokens(cacheCreation1h).build();
    }

    @Override
    public UsageTokens parseNonStreaming(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return null;
        }
        try {
            JsonNode root = JSON.readTree(responseBody);
            JsonNode usage = root.path("usage");
            if (usage.isMissingNode() || usage.isNull()) {
                log.warn("Anthropic non-streaming response has no usage field");
                return null;
            }
            JsonNode cacheCreation = usage.path("cache_creation");
            return UsageTokens.builder()
                    .inputTokens(usage.path("input_tokens").asInt())
                    .outputTokens(usage.path("output_tokens").asInt())
                    .cacheCreationTokens(usage.path("cache_creation_input_tokens").asInt())
                    .cacheReadTokens(usage.path("cache_read_input_tokens").asInt())
                    .cacheCreation5mTokens(cacheCreation.path("ephemeral_5m_input_tokens").asInt())
                    .cacheCreation1hTokens(cacheCreation.path("ephemeral_1h_input_tokens").asInt())
                    .build();
        } catch (Exception e) {
            log.warn("Failed to parse usage from Anthropic response body", e);
            return null;
        }
    }

    @Override
    public boolean isStreamDone(String sseLine) {
        if ("data: [DONE]".equals(sseLine)) {
            return true;
        }
        if (sseLine == null || !sseLine.startsWith("data: ")) {
            return false;
        }
        try {
            JsonNode root = JSON.readTree(sseLine.substring(6));
            return "message_stop".equals(root.path("type").asText());
        } catch (Exception e) {
            log.debug("Failed to parse Anthropic SSE done line", e);
            return false;
        }
    }
}
