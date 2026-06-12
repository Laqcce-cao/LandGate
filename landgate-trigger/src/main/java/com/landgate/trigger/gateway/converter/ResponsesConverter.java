package com.landgate.trigger.gateway.converter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * OpenAI Responses API ↔ IR 转换器（透传模式）。
 * <p>
 * IR 已重锚为 Responses API 格式，故 ResponsesConverter 自身即为 IR 格式，
 * 所有转换方法均为直接透传，无需任何字段映射。
 * <p>
 * 流式翻译器在透传的同时从 Responses 终止事件中提取 usage token 数。
 */
@Slf4j
@Component
public class ResponsesConverter implements ProtocolConverter {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    public String getFormatId() {
        return "responses";
    }

    // ========================
    // 请求转换（透传）
    // ========================

    /**
     * Responses 请求 → IR（透传：IR 即 Responses 格式）。
     */
    @Override
    public JsonNode requestToIR(String body) {
        try {
            return JSON.readTree(body);
        } catch (Exception e) {
            log.warn("Responses pass-through requestToIR parse error: {}", e.getMessage());
            return JSON.createObjectNode();
        }
    }

    /**
     * IR → Responses 请求（透传：IR 即 Responses 格式）。
     */
    @Override
    public String requestFromIR(JsonNode ir) {
        try {
            if (ir != null && ir.isObject()) {
                ObjectNode sanitized = ((ObjectNode) ir).deepCopy();
                sanitized.remove("_landgate_stop_sequences");
                normalizeOpenAIServiceTier(sanitized);
                sanitizeEmptyBase64InputImages(sanitized);
                return JSON.writeValueAsString(sanitized);
            }
            return JSON.writeValueAsString(ir);
        } catch (Exception e) {
            log.warn("Responses pass-through requestFromIR error: {}", e.getMessage());
            return "{}";
        }
    }

    private static void normalizeOpenAIServiceTier(ObjectNode root) {
        JsonNode value = root.get("service_tier");
        if (value == null || !value.isTextual()) {
            return;
        }
        String normalized = normalizedOpenAIServiceTierValue(value.asText());
        if (normalized.isBlank()) {
            root.remove("service_tier");
        } else {
            root.put("service_tier", normalized);
        }
    }

    private static String normalizedOpenAIServiceTierValue(String raw) {
        if (raw == null) return "";
        String value = raw.trim().toLowerCase();
        if (value.isBlank()) return "";
        if ("fast".equals(value)) {
            value = "priority";
        }
        return switch (value) {
            case "priority", "flex", "auto", "default", "scale" -> value;
            default -> "";
        };
    }

    private static void sanitizeEmptyBase64InputImages(ObjectNode root) {
        JsonNode input = root.get("input");
        if (input == null || !input.isArray()) {
            return;
        }
        var normalizedItems = JSON.createArrayNode();
        boolean changed = false;
        for (JsonNode item : input) {
            if (!(item instanceof ObjectNode itemObject)) {
                normalizedItems.add(item);
                continue;
            }
            if (shouldDropEmptyBase64InputImagePart(itemObject)) {
                changed = true;
                continue;
            }
            JsonNode content = itemObject.get("content");
            if (content == null || !content.isArray()) {
                normalizedItems.add(item);
                continue;
            }
            var normalizedParts = JSON.createArrayNode();
            boolean itemChanged = false;
            for (JsonNode part : content) {
                if (part instanceof ObjectNode partObject && shouldDropEmptyBase64InputImagePart(partObject)) {
                    changed = true;
                    itemChanged = true;
                    continue;
                }
                normalizedParts.add(part);
            }
            if (itemChanged) {
                if (normalizedParts.isEmpty()) {
                    continue;
                }
                ObjectNode copied = itemObject.deepCopy();
                copied.set("content", normalizedParts);
                normalizedItems.add(copied);
            } else {
                normalizedItems.add(item);
            }
        }
        if (changed) {
            root.set("input", normalizedItems);
        }
    }

    private static boolean shouldDropEmptyBase64InputImagePart(ObjectNode part) {
        JsonNode type = part.get("type");
        JsonNode imageUrl = part.get("image_url");
        return type != null && type.isTextual() && "input_image".equals(type.asText().trim())
                && imageUrl != null && imageUrl.isTextual()
                && isEmptyBase64DataURI(imageUrl.asText().trim());
    }

    private static boolean isEmptyBase64DataURI(String raw) {
        if (raw == null || !raw.startsWith("data:")) return false;
        String rest = raw.substring("data:".length());
        int semicolon = rest.indexOf(';');
        if (semicolon < 0) return false;
        rest = rest.substring(semicolon + 1);
        if (!rest.startsWith("base64,")) return false;
        return rest.substring("base64,".length()).trim().isEmpty();
    }

    // ========================
    // 非流式响应转换（透传）
    // ========================

    /**
     * Responses 响应 → IR（透传：IR 即 Responses 格式）。
     */
    @Override
    public JsonNode responseToIR(String body) {
        try {
            return JSON.readTree(body);
        } catch (Exception e) {
            log.warn("Responses pass-through responseToIR parse error: {}", e.getMessage());
            return JSON.createObjectNode();
        }
    }

    /**
     * IR → Responses 响应（透传：IR 即 Responses 格式）。
     */
    @Override
    public String responseFromIR(JsonNode ir) {
        try {
            return JSON.writeValueAsString(ir);
        } catch (Exception e) {
            log.warn("Responses pass-through responseFromIR error: {}", e.getMessage());
            return "{}";
        }
    }

    // ========================
    // 流式 SSE 翻译（透传 + usage 提取）
    // ========================

    /**
     * 上游 Responses SSE → IR SSE（透传，从 Responses 终止事件提取 usage）。
     */
    @Override
    public StreamTranslator createStreamToIR(String model) {
        return new PassThroughStreamTranslator();
    }

    /**
     * IR SSE → 客户端 Responses SSE（透传，从 Responses 终止事件提取 usage）。
     */
    @Override
    public StreamTranslator createStreamFromIR(String model) {
        return new PassThroughStreamTranslator();
    }

    // ========================
    // 透传流式翻译器
    // ========================

    /**
     * Responses SSE 透传翻译器 —— 逐行原样透传上游 SSE 事件，
     * 同时在遇到 Responses 终止事件时提取 usage 中的 token 数并标记流结束。
     */
    static class PassThroughStreamTranslator implements StreamTranslator {

        private boolean done = false;
        private int inputTokens = 0;
        private int outputTokens = 0;

        @Override
        public List<String> feed(String line) {
            List<String> output = new ArrayList<>();
            if (done || line == null) return output;

            // 透传所有 SSE 行（包括 event: 行、data: 行、空行分隔符）
            output.add(line);

            // 从 Responses 终止事件中提取 usage token 数
            if (line.startsWith("data: ")) {
                String json = line.substring(6);
                try {
                    JsonNode root = JSON.readTree(json);
                    String type = textOrDefault(root.get("type"), null);
                    if (isTerminalResponseEvent(type)) {
                        JsonNode usage = root.path("response").path("usage");
                        if ((usage.isMissingNode() || usage.isNull()) && root.has("usage")) {
                            usage = root.path("usage");
                        }
                        if (!usage.isMissingNode() && !usage.isNull()) {
                            int cachedTokens = nonNegativeIntOrZero(usage.path("input_tokens_details").get("cached_tokens"));
                            inputTokens = Math.max(0, nonNegativeIntOrZero(usage.get("input_tokens")) - cachedTokens);
                            outputTokens = nonNegativeIntOrZero(usage.get("output_tokens"));
                        }
                        done = true;
                    }
                } catch (Exception e) {
                    log.debug("Pass-through SSE parse error: {}", e.getMessage());
                }
            }
            return output;
        }

        @Override
        public boolean isDone() {
            return done;
        }

        @Override
        public int getInputTokens() {
            return inputTokens;
        }

        @Override
        public int getOutputTokens() {
            return outputTokens;
        }

        private static boolean isTerminalResponseEvent(String type) {
            return "response.completed".equals(type)
                    || "response.done".equals(type)
                    || "response.failed".equals(type)
                    || "response.incomplete".equals(type);
        }

        private static int nonNegativeIntOrZero(JsonNode node) {
            return node != null && node.isIntegralNumber() && node.canConvertToInt() && node.asInt() >= 0
                    ? node.asInt()
                    : 0;
        }

        private static String textOrDefault(JsonNode node, String defaultValue) {
            return node != null && node.isTextual() && !node.asText().isBlank() ? node.asText() : defaultValue;
        }
    }
}
