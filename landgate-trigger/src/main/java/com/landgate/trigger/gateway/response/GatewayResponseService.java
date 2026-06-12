package com.landgate.trigger.gateway.response;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.landgate.domain.billing.model.valobj.UsageTokens;
import com.landgate.trigger.gateway.limit.ConcurrencyService;
import com.landgate.trigger.gateway.GatewayRequestContext;
import com.landgate.trigger.gateway.usage.IUsageParser;
import com.landgate.trigger.gateway.converter.ProtocolTranslationService;
import com.landgate.trigger.gateway.converter.ConverterRegistry;
import com.landgate.trigger.gateway.converter.ProtocolConverter;
import com.landgate.trigger.gateway.converter.StreamTranslator;
import com.landgate.trigger.gateway.route.UpstreamRoute;
import com.landgate.types.enums.Platform;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class GatewayResponseService {

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
    private static final Set<String> RESPONSE_HEADER_ALLOWLIST = Set.of(
            "content-language",
            "cache-control",
            "etag",
            "last-modified",
            "expires",
            "vary",
            "date",
            "x-request-id",
            "x-ratelimit-limit-requests",
            "x-ratelimit-limit-tokens",
            "x-ratelimit-remaining-requests",
            "x-ratelimit-remaining-tokens",
            "x-ratelimit-reset-requests",
            "x-ratelimit-reset-tokens",
            "retry-after",
            "location",
            "www-authenticate");
    private static final Set<String> HOP_BY_HOP_RESPONSE_HEADERS = Set.of(
            "content-length",
            "transfer-encoding",
            "connection");

    private final ConcurrencyService concurrencyService;
    private final ProtocolTranslationService translationService;
    private final ConverterRegistry converterRegistry;

    public GatewayResponseResult handleStreaming(HttpResponse<InputStream> upstreamResp,
                                                 HttpServletResponse response,
                                                 GatewayRequestContext ctx,
                                                 IUsageParser usageParser) throws IOException {
        response.setStatus(200);
        UpstreamRoute route = ctx.getUpstreamRoute();
        copyUpstreamResponseHeaders(upstreamResp, response, route != null && route.passthrough());
        response.setContentType("text/event-stream");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("Connection", "keep-alive");
        response.setHeader("X-Accel-Buffering", "no");

        UsageTokens totalUsage = UsageTokens.builder().build();

        // 判断翻译方向，通过 ConverterRegistry 获取流式翻译器。
        // 优先使用 UpstreamRoute 中的格式，保证请求和响应翻译走同一份路由决策。
        Platform requestPlatform = ctx.getRequestPlatform();
        String clientFormat = route != null && route.clientFormat() != null
                ? route.clientFormat()
                : (ctx.getRequestFormat() != null
                        ? ctx.getRequestFormat()
                        : ProtocolTranslationService.platformToFormatId(requestPlatform));
        String upstreamFormat = route != null
                ? route.upstreamFormat()
                : ProtocolTranslationService.platformToFormatId(ctx.getSelectedAccount().getPlatform());
        boolean needTranslation = clientFormat != null && upstreamFormat != null
                && !clientFormat.equals(upstreamFormat)
                && (route == null || !route.passthrough());

        // Hub-and-Spoke 流式翻译器：上游 SSE → IR SSE，IR SSE → 客户端 SSE
        StreamTranslator upstreamToIR = null;
        StreamTranslator irToClient = null;

        if (needTranslation) {
            log.info("[{}] 流式翻译: {} -> IR -> {} | account={}",
                    ctx.getRequestId(), upstreamFormat, clientFormat, ctx.getSelectedAccount().getName());
            if (clientFormat != null && upstreamFormat != null) {
                ProtocolConverter clientConv = converterRegistry.get(clientFormat);
                ProtocolConverter upstreamConv = converterRegistry.get(upstreamFormat);
                if (clientConv != null && upstreamConv != null) {
                    upstreamToIR = upstreamConv.createStreamToIR(ctx.getRequestedModel());
                    irToClient = clientConv.createStreamFromIR(ctx.getRequestedModel());
                } else {
                    log.warn("[{}] 流式翻译器不可用: client_conv={}, upstream_conv={}, 回退为透传",
                            ctx.getRequestId(), clientConv != null, upstreamConv != null);
                }
            }
            // 若任一 Converter 不可用，upstreamToIR/irToClient 为 null，fallback 到透传
        } else {
            log.info("[{}] 流式透传模式: platform={}", ctx.getRequestId(), requestPlatform);
        }

        boolean clientDisconnected = false;
        int sseDataLines = 0;
        int usageEventLines = 0;
        boolean doneSignalSeen = false;
        try (var upstreamInput = upstreamResp.body();
             var reader = new BufferedReader(new InputStreamReader(upstreamInput, StandardCharsets.UTF_8));
             var writer = response.getWriter()) {

            String line;
            long lastRenewal = System.currentTimeMillis();
            while ((line = reader.readLine()) != null) {

                // 每 60 秒续约一次并发槽位租约，防止流式长连接期间 permit 过期
                if (System.currentTimeMillis() - lastRenewal > 60_000) {
                    if (ctx.getConcurrencySlot() != null) {
                        concurrencyService.renewLease(ctx.getConcurrencySlot());
                    }
                    lastRenewal = System.currentTimeMillis();
                }

                // 记录 SSE 结构化摘要，不输出完整响应内容，避免泄露用户数据。
                if (line.startsWith("data: ")) {
                    sseDataLines++;
                }
                if (usageParser.isStreamDone(line)) {
                    doneSignalSeen = true;
                }

                // 统一从上游原始 SSE 行解析用量，确保透传和协议翻译路径使用同一套计费来源
                if (mergeStreamingUsageFromUpstreamLine(totalUsage, usageParser, line)) {
                    usageEventLines++;
                }

                if (upstreamToIR == null || irToClient == null) {
                    // === 透传模式（无翻译或 Converter 不可用） ===
                    if (usageParser.isStreamDone(line)) {
                        writer.write(line);
                        writer.write("\n\n");
                        writer.flush();
                        break;
                    }
                    writer.write(line);
                    writer.write("\n");
                    writer.flush();
                } else {
                    // === Hub-and-Spoke 流式翻译：上游 SSE → IR SSE → 客户端 SSE ===
                    for (String irLine : upstreamToIR.feed(line)) {
                        for (String clientLine : irToClient.feed(irLine)) {
                            writer.write(clientLine);
                            writer.write("\n");
                        }
                    }
                    writer.flush();
                    if (upstreamToIR.isDone()) break;
                }
            }
        } catch (IOException e) {
            if (response.isCommitted()) {
                clientDisconnected = true;
                log.warn("[{}] 客户端在流式响应期间断开: input={}, output={}, cache_w={}, cache_r={}",
                        ctx.getRequestId(), totalUsage.getInputTokens(), totalUsage.getOutputTokens(),
                        totalUsage.getCacheCreationTokens(), totalUsage.getCacheReadTokens());
            } else {
                log.warn("SSE stream error", e);
                throw e;
            }
        }

        // Hub-and-Spoke 翻译模式下从翻译器回填用量
        if (upstreamToIR != null && irToClient != null) {
            if (totalUsage.getInputTokens() == 0 && upstreamToIR.getInputTokens() > 0) {
                totalUsage.setInputTokens(upstreamToIR.getInputTokens());
            }
            if (totalUsage.getOutputTokens() == 0 && upstreamToIR.getOutputTokens() > 0) {
                totalUsage.setOutputTokens(upstreamToIR.getOutputTokens());
            }
            // IR→Client 翻译器也可能有 token 信息
            if (totalUsage.getInputTokens() == 0 && irToClient.getInputTokens() > 0) {
                totalUsage.setInputTokens(irToClient.getInputTokens());
            }
            if (totalUsage.getOutputTokens() == 0 && irToClient.getOutputTokens() > 0) {
                totalUsage.setOutputTokens(irToClient.getOutputTokens());
            }
        }

        log.info("[{}] 流式完成: parser={}, content_type={}, data_lines={}, usage_events={}, done_seen={}, client_disconnected={}, has_usage={}, input={}, output={}, cache_w={}, cache_r={}",
                ctx.getRequestId(), usageParser.getClass().getSimpleName(),
                upstreamResp.headers().firstValue("Content-Type").orElse(""),
                sseDataLines, usageEventLines, doneSignalSeen, clientDisconnected, totalUsage.hasUsage(),
                totalUsage.getInputTokens(), totalUsage.getOutputTokens(),
                totalUsage.getCacheCreationTokens(), totalUsage.getCacheReadTokens());
        return new GatewayResponseResult(totalUsage, clientDisconnected);
    }

    /** 将上游 SSE 聚合为客户端非流式响应，适配 OpenAI OAuth Codex 仅支持上游流式的场景。 */
    public UsageTokens handleStreamingAsNonStreaming(HttpResponse<InputStream> upstreamResp,
                                                     HttpServletResponse response,
                                                     GatewayRequestContext ctx,
                                                     IUsageParser usageParser) throws IOException {
        response.setStatus(200);
        copyUpstreamResponseHeaders(upstreamResp, response, false);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        UsageTokens totalUsage = UsageTokens.builder().build();
        Map<Integer, ObjectNode> outputItems = new LinkedHashMap<>();
        Map<Integer, StringBuilder> textByContentKey = new LinkedHashMap<>();
        Map<Integer, String> contentTypeByContentKey = new LinkedHashMap<>();
        Map<Integer, StringBuilder> argumentsByOutputIndex = new LinkedHashMap<>();
        Map<Integer, StringBuilder> reasoningByOutputIndex = new LinkedHashMap<>();
        String responseId = "resp_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        String responseModel = ctx != null && ctx.getRequestedModel() != null ? ctx.getRequestedModel() : "unknown";
        String status = "completed";
        String incompleteReason = null;
        ObjectNode errorNode = null;

        try (var upstreamInput = upstreamResp.body();
             var reader = new BufferedReader(new InputStreamReader(upstreamInput, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                mergeStreamingUsageFromUpstreamLine(totalUsage, usageParser, line);
                if (!line.startsWith("data: ")) continue;

                JsonNode event;
                try {
                    event = JSON_MAPPER.readTree(line.substring(6));
                } catch (Exception e) {
                    continue;
                }
                String type = event.path("type").asText("");
                if ("response.created".equals(type) && event.has("response")) {
                    JsonNode resp = event.get("response");
                    if (resp.has("id")) responseId = resp.get("id").asText(responseId);
                    if (resp.has("model")) responseModel = resp.get("model").asText(responseModel);
                } else if ("response.output_item.added".equals(type) && event.has("item")) {
                    int outputIndex = event.path("output_index").asInt(outputItems.size());
                    ObjectNode item = normalizeStreamingOutputItem(event.get("item"));
                    outputItems.put(outputIndex, item);
                } else if ("response.output_item.done".equals(type) && event.has("item")) {
                    int outputIndex = event.path("output_index").asInt(outputItems.size());
                    JsonNode item = event.get("item");
                    outputItems.put(outputIndex, normalizeStreamingOutputItem(item));
                    mergeFinalOutputItem(outputIndex, item, textByContentKey, contentTypeByContentKey,
                            argumentsByOutputIndex, reasoningByOutputIndex);
                } else if ("response.output_text.delta".equals(type)) {
                    int outputIndex = event.path("output_index").asInt(0);
                    int contentIndex = event.path("content_index").asInt(0);
                    int contentKey = contentKey(outputIndex, contentIndex);
                    contentTypeByContentKey.put(contentKey, "output_text");
                    textByContentKey.computeIfAbsent(contentKey, ignored -> new StringBuilder())
                            .append(event.path("delta").asText(""));
                } else if ("response.output_text.done".equals(type)) {
                    int outputIndex = event.path("output_index").asInt(0);
                    int contentIndex = event.path("content_index").asInt(0);
                    String text = event.path("text").asText("");
                    if (!text.isEmpty()) {
                        int contentKey = contentKey(outputIndex, contentIndex);
                        contentTypeByContentKey.put(contentKey, "output_text");
                        StringBuilder builder = textByContentKey.computeIfAbsent(contentKey, ignored -> new StringBuilder());
                        builder.setLength(0);
                        builder.append(text);
                    }
                } else if ("response.content_part.done".equals(type)) {
                    JsonNode part = event.path("part");
                    String partType = part.path("type").asText("");
                    if ("output_text".equals(partType) || "refusal".equals(partType)) {
                        int outputIndex = event.path("output_index").asInt(0);
                        int contentIndex = event.path("content_index").asInt(0);
                        String text = "refusal".equals(partType)
                                ? part.path("refusal").asText("")
                                : part.path("text").asText("");
                        int contentKey = contentKey(outputIndex, contentIndex);
                        contentTypeByContentKey.put(contentKey, partType);
                        StringBuilder builder = textByContentKey.computeIfAbsent(contentKey, ignored -> new StringBuilder());
                        builder.setLength(0);
                        builder.append(text);
                    }
                } else if ("response.refusal.delta".equals(type)) {
                    int outputIndex = event.path("output_index").asInt(0);
                    int contentIndex = event.path("content_index").asInt(0);
                    int contentKey = contentKey(outputIndex, contentIndex);
                    contentTypeByContentKey.put(contentKey, "refusal");
                    textByContentKey.computeIfAbsent(contentKey, ignored -> new StringBuilder())
                            .append(event.path("delta").asText(""));
                } else if ("response.refusal.done".equals(type)) {
                    int outputIndex = event.path("output_index").asInt(0);
                    int contentIndex = event.path("content_index").asInt(0);
                    String refusal = event.path("refusal").asText("");
                    if (!refusal.isEmpty()) {
                        int contentKey = contentKey(outputIndex, contentIndex);
                        contentTypeByContentKey.put(contentKey, "refusal");
                        StringBuilder builder = textByContentKey.computeIfAbsent(contentKey, ignored -> new StringBuilder());
                        builder.setLength(0);
                        builder.append(refusal);
                    }
                } else if ("response.function_call_arguments.delta".equals(type)) {
                    int outputIndex = event.path("output_index").asInt(0);
                    argumentsByOutputIndex.computeIfAbsent(outputIndex, ignored -> new StringBuilder())
                            .append(event.path("delta").asText(""));
                } else if ("response.function_call_arguments.done".equals(type)) {
                    int outputIndex = event.path("output_index").asInt(0);
                    String arguments = event.path("arguments").asText("");
                    if (!arguments.isEmpty()) {
                        argumentsByOutputIndex.computeIfAbsent(outputIndex, ignored -> new StringBuilder())
                                .setLength(0);
                        argumentsByOutputIndex.get(outputIndex).append(arguments);
                    }
                } else if ("response.reasoning_summary_text.delta".equals(type)) {
                    int outputIndex = event.path("output_index").asInt(0);
                    reasoningByOutputIndex.computeIfAbsent(outputIndex, ignored -> new StringBuilder())
                            .append(event.path("delta").asText(""));
                } else if ("response.completed".equals(type) || "response.done".equals(type)) {
                    if (event.has("response")) {
                        JsonNode resp = event.get("response");
                        if (resp.has("id")) responseId = resp.get("id").asText(responseId);
                        if (resp.has("model")) responseModel = resp.get("model").asText(responseModel);
                        mergeFinalResponseOutput(resp.path("output"), outputItems, textByContentKey,
                                contentTypeByContentKey, argumentsByOutputIndex, reasoningByOutputIndex);
                        if ("incomplete".equals(resp.path("status").asText(""))) {
                            status = "incomplete";
                            incompleteReason = resp.path("incomplete_details").path("reason").asText("max_output_tokens");
                        }
                    }
                    break;
                } else if ("response.incomplete".equals(type)) {
                    status = "incomplete";
                    incompleteReason = "max_output_tokens";
                    break;
                } else if ("response.failed".equals(type)) {
                    status = "failed";
                    if (event.has("response") && event.get("response").has("error")
                            && event.get("response").get("error").isObject()) {
                        errorNode = (ObjectNode) event.get("response").get("error").deepCopy();
                    }
                    break;
                }
            }
        }

        String responsesBody = buildResponsesJson(
                responseId, responseModel, status, incompleteReason, errorNode,
                outputItems, textByContentKey, contentTypeByContentKey,
                argumentsByOutputIndex, reasoningByOutputIndex, totalUsage);
        String clientFormat = resolveClientFormat(ctx);
        String clientBody = "responses".equals(clientFormat)
                ? responsesBody
                : translationService.translateResponse(responsesBody, "responses", clientFormat);
        try (var output = response.getOutputStream()) {
            output.write(clientBody.getBytes(StandardCharsets.UTF_8));
            output.flush();
        }

        log.info("[{}] 流式聚合完成: has_usage={}, input={}, output={}, cache_w={}, cache_r={}",
                ctx != null ? ctx.getRequestId() : "?", totalUsage.hasUsage(),
                totalUsage.getInputTokens(), totalUsage.getOutputTokens(),
                totalUsage.getCacheCreationTokens(), totalUsage.getCacheReadTokens());
        return totalUsage;
    }

    private static ObjectNode normalizeStreamingOutputItem(JsonNode item) {
        ObjectNode normalized = JSON_MAPPER.createObjectNode();
        String type = item.path("type").asText("message");
        normalized.put("type", type);
        if (item.has("id")) normalized.set("id", item.get("id"));
        if ("function_call".equals(type)) {
            normalized.put("call_id", item.path("call_id").asText(""));
            normalized.put("name", item.path("name").asText(""));
            normalized.put("arguments", item.path("arguments").asText("{}"));
            normalized.put("status", "completed");
        } else if ("reasoning".equals(type)) {
            normalized.put("status", "completed");
            normalized.set("summary", item.has("summary") ? item.get("summary") : JSON_MAPPER.createArrayNode());
        } else {
            normalized.put("type", "message");
            normalized.put("role", item.path("role").asText("assistant"));
            normalized.put("status", "completed");
            normalized.set("content", JSON_MAPPER.createArrayNode());
        }
        return normalized;
    }

    private static void mergeFinalResponseOutput(JsonNode output,
                                                 Map<Integer, ObjectNode> outputItems,
                                                 Map<Integer, StringBuilder> textByContentKey,
                                                 Map<Integer, String> contentTypeByContentKey,
                                                 Map<Integer, StringBuilder> argumentsByOutputIndex,
                                                 Map<Integer, StringBuilder> reasoningByOutputIndex) {
        if (output == null || !output.isArray()) return;
        for (int i = 0; i < output.size(); i++) {
            JsonNode item = output.get(i);
            outputItems.put(i, normalizeStreamingOutputItem(item));
            mergeFinalOutputItem(i, item, textByContentKey, contentTypeByContentKey,
                    argumentsByOutputIndex, reasoningByOutputIndex);
        }
    }

    private static void mergeFinalOutputItem(int outputIndex,
                                             JsonNode item,
                                             Map<Integer, StringBuilder> textByContentKey,
                                             Map<Integer, String> contentTypeByContentKey,
                                             Map<Integer, StringBuilder> argumentsByOutputIndex,
                                             Map<Integer, StringBuilder> reasoningByOutputIndex) {
        String itemType = item.path("type").asText("");
        if ("message".equals(itemType) && item.has("content") && item.get("content").isArray()) {
            int contentIndex = 0;
            for (JsonNode part : item.get("content")) {
                String partType = part.path("type").asText("");
                if ("output_text".equals(partType) || "refusal".equals(partType)) {
                    String text = "refusal".equals(partType)
                            ? part.path("refusal").asText("")
                            : part.path("text").asText("");
                    int key = contentKey(outputIndex, contentIndex);
                    contentTypeByContentKey.put(key, partType);
                    StringBuilder builder = textByContentKey.computeIfAbsent(key, ignored -> new StringBuilder());
                    builder.setLength(0);
                    builder.append(text);
                }
                contentIndex++;
            }
        } else if ("function_call".equals(itemType)) {
            if (item.has("arguments")) {
                StringBuilder builder = argumentsByOutputIndex.computeIfAbsent(outputIndex, ignored -> new StringBuilder());
                builder.setLength(0);
                builder.append(item.path("arguments").asText("{}"));
            }
        } else if ("reasoning".equals(itemType) && item.has("summary") && item.get("summary").isArray()) {
            StringBuilder builder = reasoningByOutputIndex.computeIfAbsent(outputIndex, ignored -> new StringBuilder());
            builder.setLength(0);
            for (JsonNode summary : item.get("summary")) {
                if (!"summary_text".equals(summary.path("type").asText(""))) continue;
                if (builder.length() > 0) builder.append("\n");
                builder.append(summary.path("text").asText(""));
            }
        }
    }

    private static String buildResponsesJson(String responseId,
                                             String responseModel,
                                             String status,
                                             String incompleteReason,
                                             ObjectNode errorNode,
                                             Map<Integer, ObjectNode> outputItems,
                                             Map<Integer, StringBuilder> textByContentKey,
                                             Map<Integer, String> contentTypeByContentKey,
                                             Map<Integer, StringBuilder> argumentsByOutputIndex,
                                             Map<Integer, StringBuilder> reasoningByOutputIndex,
                                             UsageTokens usage) throws IOException {
        Map<Integer, ArrayNode> contentByOutputIndex = new LinkedHashMap<>();
        textByContentKey.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    int outputIndex = outputIndexFromContentKey(entry.getKey());
                    String partType = contentTypeByContentKey.getOrDefault(entry.getKey(), "output_text");
                    ObjectNode text = JSON_MAPPER.createObjectNode();
                    text.put("type", partType);
                    if ("refusal".equals(partType)) {
                        text.put("refusal", entry.getValue().toString());
                    } else {
                        text.put("text", entry.getValue().toString());
                    }
                    contentByOutputIndex.computeIfAbsent(outputIndex, ignored -> JSON_MAPPER.createArrayNode())
                            .add(text);
                });

        for (Map.Entry<Integer, ArrayNode> entry : contentByOutputIndex.entrySet()) {
            ObjectNode item = outputItems.computeIfAbsent(entry.getKey(), ignored -> {
                ObjectNode message = JSON_MAPPER.createObjectNode();
                message.put("type", "message");
                message.put("role", "assistant");
                message.put("status", "completed");
                message.set("content", JSON_MAPPER.createArrayNode());
                return message;
            });
            if (!"message".equals(item.path("type").asText())) continue;
            item.set("content", entry.getValue());
        }

        for (Map.Entry<Integer, StringBuilder> entry : argumentsByOutputIndex.entrySet()) {
            ObjectNode item = outputItems.get(entry.getKey());
            if (item != null && "function_call".equals(item.path("type").asText())) {
                String arguments = entry.getValue().toString();
                item.put("arguments", arguments.isEmpty() ? "{}" : arguments);
                item.put("status", "completed");
            }
        }

        for (Map.Entry<Integer, StringBuilder> entry : reasoningByOutputIndex.entrySet()) {
            ObjectNode item = outputItems.computeIfAbsent(entry.getKey(), ignored -> {
                ObjectNode reasoning = JSON_MAPPER.createObjectNode();
                reasoning.put("type", "reasoning");
                reasoning.put("status", "completed");
                reasoning.set("summary", JSON_MAPPER.createArrayNode());
                return reasoning;
            });
            if (!"reasoning".equals(item.path("type").asText())) continue;
            ArrayNode summary = JSON_MAPPER.createArrayNode();
            ObjectNode text = JSON_MAPPER.createObjectNode();
            text.put("type", "summary_text");
            text.put("text", entry.getValue().toString());
            summary.add(text);
            item.set("summary", summary);
            item.put("status", "completed");
        }

        if (outputItems.isEmpty()) {
            ObjectNode message = JSON_MAPPER.createObjectNode();
            message.put("type", "message");
            message.put("role", "assistant");
            message.put("status", "completed");
            ArrayNode content = JSON_MAPPER.createArrayNode();
            ObjectNode text = JSON_MAPPER.createObjectNode();
            text.put("type", "output_text");
            text.put("text", "");
            content.add(text);
            message.set("content", content);
            outputItems.put(0, message);
        }

        ObjectNode root = JSON_MAPPER.createObjectNode();
        root.put("id", responseId);
        root.put("object", "response");
        root.put("model", responseModel);
        root.put("status", status == null || status.isBlank() ? "completed" : status);
        if ("incomplete".equals(status)) {
            ObjectNode details = JSON_MAPPER.createObjectNode();
            details.put("reason", incompleteReason == null || incompleteReason.isBlank()
                    ? "max_output_tokens"
                    : incompleteReason);
            root.set("incomplete_details", details);
        }
        if ("failed".equals(status) && errorNode != null) {
            root.set("error", errorNode);
        }
        ArrayNode output = JSON_MAPPER.createArrayNode();
        outputItems.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> output.add(entry.getValue()));
        root.set("output", output);

        ObjectNode usageNode = JSON_MAPPER.createObjectNode();
        int inputTokens = usage != null ? usage.getInputTokens() : 0;
        int cachedTokens = usage != null ? usage.getCacheReadTokens() : 0;
        int outputTokens = usage != null ? usage.getOutputTokens() : 0;
        usageNode.put("input_tokens", inputTokens + cachedTokens);
        usageNode.put("output_tokens", outputTokens);
        usageNode.put("total_tokens", inputTokens + cachedTokens + outputTokens);
        if (cachedTokens > 0) {
            ObjectNode inputDetails = JSON_MAPPER.createObjectNode();
            inputDetails.put("cached_tokens", cachedTokens);
            usageNode.set("input_tokens_details", inputDetails);
        }
        root.set("usage", usageNode);

        return JSON_MAPPER.writeValueAsString(root);
    }

    private static int contentKey(int outputIndex, int contentIndex) {
        return outputIndex * 10_000 + contentIndex;
    }

    private static int outputIndexFromContentKey(int contentKey) {
        return contentKey / 10_000;
    }

    private static String resolveClientFormat(GatewayRequestContext ctx) {
        if (ctx == null) return "responses";
        UpstreamRoute route = ctx.getUpstreamRoute();
        if (route != null && route.clientFormat() != null) {
            return route.clientFormat();
        }
        if (ctx.getRequestFormat() != null) {
            return ctx.getRequestFormat();
        }
        return ProtocolTranslationService.platformToFormatId(ctx.getRequestPlatform());
    }

    private static void copyUpstreamResponseHeaders(HttpResponse<?> upstreamResp,
                                                    HttpServletResponse response,
                                                    boolean passthrough) {
        if (upstreamResp == null || response == null || upstreamResp.headers() == null) {
            return;
        }
        for (Map.Entry<String, List<String>> entry : upstreamResp.headers().map().entrySet()) {
            String name = entry.getKey();
            if (name == null || name.isBlank()) {
                continue;
            }
            String lower = name.toLowerCase();
            if (HOP_BY_HOP_RESPONSE_HEADERS.contains(lower) || "content-type".equals(lower)) {
                continue;
            }
            boolean allowed = RESPONSE_HEADER_ALLOWLIST.contains(lower)
                    || (passthrough && lower.startsWith("x-codex-"));
            if (!allowed) {
                continue;
            }
            for (String value : entry.getValue()) {
                if (value != null && !value.isBlank()) {
                    response.addHeader(name, value);
                }
            }
        }
    }

    public UsageTokens handleNonStreaming(HttpResponse<InputStream> upstreamResp,
                                          HttpServletResponse response,
                                          IUsageParser usageParser) throws IOException {
        GatewayRequestContext ctx = GatewayRequestContext.get();
        UpstreamRoute route = ctx != null ? ctx.getUpstreamRoute() : null;
        boolean passthrough = route != null && route.passthrough();
        response.setStatus(upstreamResp.statusCode());
        copyUpstreamResponseHeaders(upstreamResp, response, passthrough);
        response.setContentType(passthrough
                ? upstreamResp.headers().firstValue("Content-Type").orElse("application/json")
                : "application/json");
        response.setCharacterEncoding("UTF-8");

        String responseBody;
        try (var input = upstreamResp.body()) {
            responseBody = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        // 先解析用量（用上游格式），再做响应协议翻译
        UsageTokens usage = usageParser.parseNonStreaming(responseBody);

        // 协议翻译：上游格式 → 客户端格式。
        // 优先使用 UpstreamRoute 中的格式，保证与请求翻译、usage parser 的路由决策一致。
        log.info("[{}] 非流式用量解析: parser={}, body_bytes={}, has_usage={}, input={}, output={}, cache_w={}, cache_r={}",
                ctx != null ? ctx.getRequestId() : "?",
                usageParser.getClass().getSimpleName(),
                responseBody.getBytes(StandardCharsets.UTF_8).length,
                usage != null && usage.hasUsage(),
                usage != null ? usage.getInputTokens() : 0,
                usage != null ? usage.getOutputTokens() : 0,
                usage != null ? usage.getCacheCreationTokens() : 0,
                usage != null ? usage.getCacheReadTokens() : 0);
        Platform requestPlatform = ctx != null ? ctx.getRequestPlatform() : null;
        String clientFormat = route != null && route.clientFormat() != null
                ? route.clientFormat()
                : (ctx != null && ctx.getRequestFormat() != null
                        ? ctx.getRequestFormat()
                        : ProtocolTranslationService.platformToFormatId(requestPlatform));
        String upstreamFormat = route != null
                ? route.upstreamFormat()
                : (ctx != null && ctx.getSelectedAccount() != null
                        ? ProtocolTranslationService.platformToFormatId(ctx.getSelectedAccount().getPlatform())
                        : null);
        boolean needRespTranslation = clientFormat != null && upstreamFormat != null
                && !clientFormat.equals(upstreamFormat)
                && (route == null || !route.passthrough());
        String clientBody = responseBody;
        if (needRespTranslation) {
            log.info("[{}] 响应协议翻译: {} -> {}",
                    ctx != null ? ctx.getRequestId() : "?", upstreamFormat, clientFormat);
            clientBody = translationService.translateResponse(responseBody, upstreamFormat, clientFormat);
        }

        try (var output = response.getOutputStream()) {
            output.write(clientBody.getBytes(StandardCharsets.UTF_8));
            output.flush();
        }

        return usage;
    }

    public void writePassthroughError(HttpResponse<InputStream> upstreamResp,
                                      HttpServletResponse response,
                                      String responseBody) throws IOException {
        response.setStatus(upstreamResp.statusCode());
        copyUpstreamResponseHeaders(upstreamResp, response, true);
        response.setContentType(upstreamResp.headers().firstValue("Content-Type").orElse("application/json"));
        response.setCharacterEncoding("UTF-8");
        try (var output = response.getOutputStream()) {
            output.write((responseBody == null ? "" : responseBody).getBytes(StandardCharsets.UTF_8));
            output.flush();
        }
    }

    /** 构造 Anthropic Messages 非流式响应 JSON。 */
    private String buildAnthropicMessageJson(String id, String model, String text, String stopReason, UsageTokens usage)
            throws IOException {
        var root = JSON_MAPPER.createObjectNode();
        root.put("id", id);
        root.put("type", "message");
        root.put("role", "assistant");
        root.put("model", model);
        var content = JSON_MAPPER.createArrayNode();
        var textBlock = JSON_MAPPER.createObjectNode();
        textBlock.put("type", "text");
        textBlock.put("text", text);
        content.add(textBlock);
        root.set("content", content);
        root.put("stop_reason", stopReason);
        root.putNull("stop_sequence");
        var usageNode = JSON_MAPPER.createObjectNode();
        usageNode.put("input_tokens", usage != null ? usage.getInputTokens() : 0);
        usageNode.put("output_tokens", usage != null ? usage.getOutputTokens() : 0);
        if (usage != null && usage.getCacheCreationTokens() > 0) {
            usageNode.put("cache_creation_input_tokens", usage.getCacheCreationTokens());
        }
        if (usage != null && usage.getCacheReadTokens() > 0) {
            usageNode.put("cache_read_input_tokens", usage.getCacheReadTokens());
        }
        root.set("usage", usageNode);
        return JSON_MAPPER.writeValueAsString(root);
    }

    /** 从上游原始 SSE 行解析用量，避免协议翻译路径丢失缓存 Token。 */
    private boolean mergeStreamingUsageFromUpstreamLine(UsageTokens totalUsage,
                                                        IUsageParser usageParser,
                                                        String line) {
        if (totalUsage == null || usageParser == null || line == null) return false;
        if (!line.startsWith("data: ")) return false;

        UsageTokens eventUsage = usageParser.parseSSELine(line.substring(6));
        if (eventUsage != null) {
            totalUsage.merge(eventUsage);
            return eventUsage.hasUsage();
        }
        return false;
    }
}
