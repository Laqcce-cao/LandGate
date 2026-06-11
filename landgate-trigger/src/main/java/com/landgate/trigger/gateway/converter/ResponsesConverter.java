package com.landgate.trigger.gateway.converter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * 流式翻译器在透传的同时从 {@code response.completed} 事件中提取 usage token 数。
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
            return JSON.writeValueAsString(ir);
        } catch (Exception e) {
            log.warn("Responses pass-through requestFromIR error: {}", e.getMessage());
            return "{}";
        }
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
     * 上游 Responses SSE → IR SSE（透传，从 response.completed 提取 usage）。
     */
    @Override
    public StreamTranslator createStreamToIR(String model) {
        return new PassThroughStreamTranslator();
    }

    /**
     * IR SSE → 客户端 Responses SSE（透传，从 response.completed 提取 usage）。
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
     * 同时在遇到 {@code response.completed} 事件时提取 usage 中的 token 数并标记流结束。
     */
    static class PassThroughStreamTranslator implements StreamTranslator {

        private boolean done = false;
        private boolean terminalSeparatorPending = false;
        private int inputTokens = 0;
        private int outputTokens = 0;

        @Override
        public List<String> feed(String line) {
            List<String> output = new ArrayList<>();
            if (line == null) return output;
            if (done) {
                if (terminalSeparatorPending && line.isBlank()) {
                    output.add(line);
                    terminalSeparatorPending = false;
                }
                return output;
            }

            // 透传所有 SSE 行（包括 event: 行、data: 行、空行分隔符）
            output.add(line);

            // 从 response.completed 事件中提取 usage token 数
            if (line.startsWith("data: ")) {
                String json = line.substring(6);
                try {
                    JsonNode root = JSON.readTree(json);
                    String type = root.has("type") ? root.get("type").asText() : null;
                    if ("response.completed".equals(type)) {
                        if (root.has("response") && root.get("response").has("usage")) {
                            JsonNode usage = root.get("response").get("usage");
                            if (usage.has("input_tokens")) inputTokens = usage.get("input_tokens").asInt();
                            if (usage.has("output_tokens")) outputTokens = usage.get("output_tokens").asInt();
                        }
                        done = true;
                        terminalSeparatorPending = true;
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
    }
}
