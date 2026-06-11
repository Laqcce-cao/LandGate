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
            JsonNode usage = findUsageNode(root);
            if (usage == null) {
                return new UsageTokens();
            }
            return toUsageTokens(usage);
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
            JsonNode usage = findUsageNode(root);
            if (usage == null) {
                return null;
            }
            return toUsageTokens(usage);
        } catch (Exception e) {
            log.debug("Failed to parse OpenAI SSE line", e);
            return null;
        }
    }

    @Override
    public boolean isStreamDone(String sseLine) {
        return "data: [DONE]".equals(sseLine);
    }

    private JsonNode findUsageNode(JsonNode root) {
        if (root == null || root.isMissingNode() || root.isNull()) {
            return null;
        }
        JsonNode usage = root.path("usage");
        if (!usage.isMissingNode() && !usage.isNull()) {
            return usage;
        }
        usage = root.path("response").path("usage");
        if (!usage.isMissingNode() && !usage.isNull()) {
            return usage;
        }
        JsonNode choices = root.path("choices");
        if (choices.isArray()) {
            for (JsonNode choice : choices) {
                usage = choice.path("usage");
                if (!usage.isMissingNode() && !usage.isNull()) {
                    return usage;
                }
            }
        }
        return null;
    }

    private UsageTokens toUsageTokens(JsonNode usage) {
        int rawInputTokens = firstInt(usage, "prompt_tokens", "input_tokens");
        int outputTokens = firstInt(usage, "completion_tokens", "output_tokens");
        int cachedTokens = firstNestedInt(usage,
                new String[]{"prompt_tokens_details", "cached_tokens"},
                new String[]{"input_tokens_details", "cached_tokens"});
        return UsageTokens.builder()
                .inputTokens(Math.max(0, rawInputTokens - cachedTokens))
                .outputTokens(outputTokens)
                .cacheReadTokens(cachedTokens)
                .build();
    }

    private int firstInt(JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            JsonNode value = node.path(fieldName);
            if (!value.isMissingNode() && !value.isNull()) {
                return value.asInt();
            }
        }
        return 0;
    }

    private int firstNestedInt(JsonNode node, String[]... paths) {
        for (String[] path : paths) {
            JsonNode value = node;
            for (String fieldName : path) {
                value = value.path(fieldName);
            }
            if (!value.isMissingNode() && !value.isNull()) {
                return value.asInt();
            }
        }
        return 0;
    }
}
