package com.landgate.trigger.gateway.converter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Anthropic Messages API 格式 ↔ IR 转换器。
 * <p>
 * 因为 IR 本身采用 Anthropic Messages API 格式，此 Converter 基本为 pass-through，
 * 仅做字段标准化和验证。流式 SSE 翻译器负责 Anthropic SSE ↔ IR SSE 双向转换。
 */
@Slf4j
@Component
public class AnthropicConverter implements ProtocolConverter {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    public String getFormatId() {
        return "messages";
    }

    // ========================
    // 请求转换（pass-through）
    // ========================

    @Override
    public JsonNode requestToIR(String body) {
        try {
            return JSON.readTree(body);
        } catch (Exception e) {
            log.warn("Failed to parse Anthropic→IR request: {}", e.getMessage());
            return JSON.createObjectNode();
        }
    }

    @Override
    public String requestFromIR(JsonNode ir) {
        try {
            return JSON.writeValueAsString(ir);
        } catch (Exception e) {
            log.warn("Failed to serialize IR→Anthropic request: {}", e.getMessage());
            return "{}";
        }
    }

    // ========================
    // 响应转换（pass-through）
    // ========================

    @Override
    public JsonNode responseToIR(String body) {
        try {
            return JSON.readTree(body);
        } catch (Exception e) {
            log.warn("Failed to parse Anthropic→IR response: {}", e.getMessage());
            return JSON.createObjectNode();
        }
    }

    @Override
    public String responseFromIR(JsonNode ir) {
        try {
            return JSON.writeValueAsString(ir);
        } catch (Exception e) {
            log.warn("Failed to serialize IR→Anthropic response: {}", e.getMessage());
            return "{}";
        }
    }

    // ========================
    // 流式 SSE 翻译
    // ========================

    /**
     * 将 Anthropic SSE 事件转换为 IR SSE 事件（pass-through）。
     */
    @Override
    public StreamTranslator createStreamToIR(String model) {
        return new AnthropicToIRStreamTranslator();
    }

    /**
     * 将 IR SSE 事件转换为 Anthropic SSE 事件（pass-through）。
     */
    @Override
    public StreamTranslator createStreamFromIR(String model) {
        return new IRToAnthropicStreamTranslator(model);
    }

    // ========================
    // 流式翻译器内部类
    // ========================

    /**
     * Anthropic SSE → IR SSE（pass-through，提取 token 用量）。
     */
    static class AnthropicToIRStreamTranslator implements StreamTranslator {

        private boolean done = false;
        private int inputTokens = 0;
        private int outputTokens = 0;
        private final List<String> buffer = new ArrayList<>();

        @Override
        public List<String> feed(String line) {
            List<String> output = new ArrayList<>();
            if (done || line == null || line.isBlank()) {
                return output;
            }

            // 透传所有行
            output.add(line);

            // 检测 message_stop 结束
            if (line.startsWith("data: ")) {
                String json = line.substring(6);
                try {
                    JsonNode root = JSON.readTree(json);
                    if (root.has("type")) {
                        String type = root.get("type").asText();
                        if ("message_start".equals(type) && root.has("message")) {
                            JsonNode msg = root.get("message");
                            if (msg.has("usage") && msg.get("usage").has("input_tokens")) {
                                inputTokens = msg.get("usage").get("input_tokens").asInt();
                            }
                        }
                        if ("message_delta".equals(type) && root.has("usage") && root.get("usage").has("output_tokens")) {
                            outputTokens = root.get("usage").get("output_tokens").asInt();
                        }
                        if ("message_stop".equals(type)) {
                            done = true;
                        }
                    }
                } catch (Exception e) {
                    log.debug("Anthropic→IR SSE parse: {}", e.getMessage());
                }
            }
            return output;
        }

        @Override
        public boolean isDone() { return done; }

        @Override
        public int getInputTokens() { return inputTokens; }

        @Override
        public int getOutputTokens() { return outputTokens; }
    }

    /**
     * IR SSE → Anthropic SSE（pass-through）。
     */
    static class IRToAnthropicStreamTranslator implements StreamTranslator {

        private boolean done = false;
        private int inputTokens = 0;
        private int outputTokens = 0;

        @SuppressWarnings("unused")
        private final String model;

        IRToAnthropicStreamTranslator(String model) {
            this.model = model;
        }

        @Override
        public List<String> feed(String line) {
            List<String> output = new ArrayList<>();
            if (done || line == null || line.isBlank()) {
                return output;
            }
            output.add(line);

            // 检测 message_stop 结束
            if (line.startsWith("data: ")) {
                String json = line.substring(6);
                try {
                    JsonNode root = JSON.readTree(json);
                    if (root.has("type")) {
                        if ("message_stop".equals(root.get("type").asText())) {
                            done = true;
                        }
                        if ("message_delta".equals(root.get("type").asText())
                                && root.has("usage") && root.get("usage").has("output_tokens")) {
                            outputTokens = root.get("usage").get("output_tokens").asInt();
                        }
                    }
                } catch (Exception e) {
                    log.debug("IR→Anthropic SSE parse: {}", e.getMessage());
                }
            }
            return output;
        }

        @Override
        public boolean isDone() { return done; }

        @Override
        public int getInputTokens() { return inputTokens; }

        @Override
        public int getOutputTokens() { return outputTokens; }
    }
}
