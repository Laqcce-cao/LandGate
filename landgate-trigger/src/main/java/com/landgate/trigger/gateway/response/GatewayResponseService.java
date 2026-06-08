package com.landgate.trigger.gateway.response;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.landgate.domain.billing.model.valobj.UsageTokens;
import com.landgate.trigger.gateway.ConcurrencyService;
import com.landgate.trigger.gateway.GatewayRequestContext;
import com.landgate.trigger.gateway.usage.IUsageParser;
import com.landgate.trigger.gateway.ProtocolTranslationService;
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
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class GatewayResponseService {

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    private final ConcurrencyService concurrencyService;
    private final ProtocolTranslationService translationService;
    private final ConverterRegistry converterRegistry;

    public GatewayResponseResult handleStreaming(HttpResponse<InputStream> upstreamResp,
                                                 HttpServletResponse response,
                                                 GatewayRequestContext ctx,
                                                 IUsageParser usageParser) throws IOException {
        response.setStatus(200);
        response.setContentType("text/event-stream");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("Connection", "keep-alive");
        response.setHeader("X-Accel-Buffering", "no");

        UsageTokens totalUsage = UsageTokens.builder().build();

        // 判断翻译方向，通过 ConverterRegistry 获取流式翻译器。
        // 优先使用 UpstreamRoute 中的格式，保证请求和响应翻译走同一份路由决策。
        Platform requestPlatform = ctx.getRequestPlatform();
        UpstreamRoute route = ctx.getUpstreamRoute();
        String clientFormat = route != null && route.clientFormat() != null
                ? route.clientFormat()
                : (ctx.getRequestFormat() != null
                        ? ctx.getRequestFormat()
                        : ProtocolTranslationService.platformToFormatId(requestPlatform));
        String upstreamFormat = route != null
                ? route.upstreamFormat()
                : ProtocolTranslationService.platformToFormatId(ctx.getSelectedAccount().getPlatform());
        boolean needTranslation = clientFormat != null && upstreamFormat != null
                && !clientFormat.equals(upstreamFormat);

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
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        UsageTokens totalUsage = UsageTokens.builder().build();
        StringBuilder responseText = new StringBuilder();
        String responseId = "msg_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        String responseModel = ctx != null && ctx.getRequestedModel() != null ? ctx.getRequestedModel() : "unknown";
        String stopReason = "end_turn";

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
                } else if ("response.output_text.delta".equals(type)) {
                    responseText.append(event.path("delta").asText(""));
                } else if ("response.completed".equals(type) || "response.done".equals(type)) {
                    if (event.has("response")) {
                        JsonNode resp = event.get("response");
                        if (resp.has("id")) responseId = resp.get("id").asText(responseId);
                        if (resp.has("model")) responseModel = resp.get("model").asText(responseModel);
                        if ("incomplete".equals(resp.path("status").asText(""))) {
                            String reason = resp.path("incomplete_details").path("reason").asText("");
                            stopReason = "max_output_tokens".equals(reason) ? "max_tokens" : "end_turn";
                        }
                    }
                    break;
                } else if ("response.incomplete".equals(type)) {
                    stopReason = "max_tokens";
                    break;
                } else if ("response.failed".equals(type)) {
                    stopReason = "end_turn";
                    break;
                }
            }
        }

        String clientBody = buildAnthropicMessageJson(responseId, responseModel, responseText.toString(), stopReason, totalUsage);
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

    public UsageTokens handleNonStreaming(HttpResponse<InputStream> upstreamResp,
                                          HttpServletResponse response,
                                          IUsageParser usageParser) throws IOException {
        response.setStatus(upstreamResp.statusCode());
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        String responseBody;
        try (var input = upstreamResp.body()) {
            responseBody = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        // 先解析用量（用上游格式），再做响应协议翻译
        UsageTokens usage = usageParser.parseNonStreaming(responseBody);

        // 协议翻译：上游格式 → 客户端格式。
        // 优先使用 UpstreamRoute 中的格式，保证与请求翻译、usage parser 的路由决策一致。
        GatewayRequestContext ctx = GatewayRequestContext.get();
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
        UpstreamRoute route = ctx != null ? ctx.getUpstreamRoute() : null;
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
                && !clientFormat.equals(upstreamFormat);
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
