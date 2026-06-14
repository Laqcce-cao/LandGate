package com.landgate.trigger.gateway.response;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.landgate.domain.billing.model.valobj.UsageTokens;
import com.landgate.trigger.gateway.billing.AnthropicCacheTtlUsageOverrideService;
import com.landgate.trigger.gateway.limit.ConcurrencyService;
import com.landgate.trigger.gateway.GatewayRequestContext;
import com.landgate.trigger.gateway.usage.IUsageParser;
import com.landgate.trigger.gateway.converter.ProtocolFormatResolver;
import com.landgate.trigger.gateway.converter.ProtocolTranslationService;
import com.landgate.trigger.gateway.converter.ConverterRegistry;
import com.landgate.trigger.gateway.converter.ProtocolConverter;
import com.landgate.trigger.gateway.converter.StreamTranslator;
import com.landgate.trigger.gateway.route.UpstreamRoute;
import com.landgate.types.enums.Platform;
import com.landgate.types.gateway.GatewayHttpHeaderPolicy;
import com.landgate.types.gateway.GatewayResponseContentPolicy;
import com.landgate.types.gateway.GatewayResponseHeaderPolicy;
import com.landgate.types.gateway.GatewayProtocolFormat;
import com.landgate.types.gateway.GatewayStreamAggregationPolicy;
import com.landgate.types.gateway.AnthropicMessagesBodyPolicy;
import com.landgate.types.gateway.OpenAiResponsesSsePolicy;
import com.landgate.types.gateway.OpenAiResponsesJsonPolicy;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class GatewayResponseService {

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
    private final ConcurrencyService concurrencyService;
    private final ProtocolTranslationService translationService;
    private final ConverterRegistry converterRegistry;
    private final AnthropicCacheTtlUsageOverrideService cacheTtlUsageOverrideService;
    private final AnthropicUsageCompatibilityService anthropicUsageCompatibilityService;

    public GatewayResponseService(ConcurrencyService concurrencyService,
                                  ProtocolTranslationService translationService,
                                  ConverterRegistry converterRegistry) {
        this(concurrencyService, translationService, converterRegistry,
                new AnthropicCacheTtlUsageOverrideService(),
                new AnthropicUsageCompatibilityService());
    }

    @Autowired
    public GatewayResponseService(ConcurrencyService concurrencyService,
                                  ProtocolTranslationService translationService,
                                  ConverterRegistry converterRegistry,
                                  AnthropicCacheTtlUsageOverrideService cacheTtlUsageOverrideService,
                                  AnthropicUsageCompatibilityService anthropicUsageCompatibilityService) {
        this.concurrencyService = concurrencyService;
        this.translationService = translationService;
        this.converterRegistry = converterRegistry;
        this.cacheTtlUsageOverrideService = cacheTtlUsageOverrideService;
        this.anthropicUsageCompatibilityService = anthropicUsageCompatibilityService;
    }

    public GatewayResponseResult handleStreaming(HttpResponse<InputStream> upstreamResp,
                                                 HttpServletResponse response,
                                                 GatewayRequestContext ctx,
                                                 IUsageParser usageParser) throws IOException {
        response.setStatus(200);
        UpstreamRoute route = ctx.getUpstreamRoute();
        boolean passthrough = isPassthrough(ctx, route);
        copyUpstreamResponseHeaders(upstreamResp, response, passthrough);
        response.setContentType(GatewayResponseContentPolicy.MEDIA_TYPE_EVENT_STREAM);
        response.setCharacterEncoding(GatewayResponseContentPolicy.CHARSET_UTF_8);
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("Connection", "keep-alive");
        response.setHeader("X-Accel-Buffering", "no");

        UsageTokens totalUsage = UsageTokens.builder().build();
        String responseId = "";

        // 判断翻译方向，通过 ConverterRegistry 获取流式翻译器。
        // 优先使用 UpstreamRoute 中的格式，保证请求和响应翻译走同一份路由决策。
        Platform requestPlatform = ctx.getRequestPlatform();
        String clientFormat = resolveClientFormat(ctx, route);
        String upstreamFormat = resolveUpstreamFormat(ctx, route);
        boolean needTranslation = clientFormat != null && upstreamFormat != null
                && !clientFormat.equals(upstreamFormat);

        // Hub-and-Spoke 流式翻译器：上游 SSE → IR SSE，IR SSE → 客户端 SSE
        StreamTranslator upstreamToIR = null;
        StreamTranslator irToClient = null;

        if (needTranslation) {
            log.debug("[{}] 流式翻译: {} -> IR -> {} | account={}",
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
            log.debug("[{}] 流式透传模式: platform={}", ctx.getRequestId(), requestPlatform);
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
                line = restoreAnthropicToolNames(ctx, line);
                line = normalizeAnthropicUsageSseLine(ctx, route, line);

                // 每 60 秒续约一次并发槽位租约，防止流式长连接期间 permit 过期
                if (System.currentTimeMillis() - lastRenewal > 60_000) {
                    if (ctx.getConcurrencySlot() != null) {
                        concurrencyService.renewLease(ctx.getConcurrencySlot());
                    }
                    lastRenewal = System.currentTimeMillis();
                }

                // 记录 SSE 结构化摘要，不输出完整响应内容，避免泄露用户数据。
                if (OpenAiResponsesSsePolicy.extractDataPayload(line) != null) {
                    sseDataLines++;
                    responseId = firstNonBlank(responseId, extractResponseIdFromSseLine(line));
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
                    if (!clientDisconnected && !writeSseLine(writer, line, usageParser.isStreamDone(line))) {
                        clientDisconnected = true;
                        logClientDisconnected(ctx, totalUsage);
                    }
                    if (usageParser.isStreamDone(line)) {
                        break;
                    }
                } else {
                    // === Hub-and-Spoke 流式翻译：上游 SSE → IR SSE → 客户端 SSE ===
                    for (String irLine : upstreamToIR.feed(line)) {
                        for (String clientLine : irToClient.feed(irLine)) {
                            if (!clientDisconnected && !writeSseLine(writer, clientLine, false)) {
                                clientDisconnected = true;
                                logClientDisconnected(ctx, totalUsage);
                            }
                        }
                    }
                    if (!clientDisconnected) {
                        writer.flush();
                        if (writer.checkError()) {
                            clientDisconnected = true;
                            logClientDisconnected(ctx, totalUsage);
                        }
                    }
                    if (upstreamToIR.isDone()) break;
                }
            }
        } catch (IOException e) {
            if (clientDisconnected || response.isCommitted()) {
                clientDisconnected = true;
                logClientDisconnected(ctx, totalUsage);
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

        log.debug("[{}] 流式完成: parser={}, content_type={}, data_lines={}, usage_events={}, done_seen={}, client_disconnected={}, has_usage={}, input={}, output={}, cache_w={}, cache_r={}",
                ctx.getRequestId(), usageParser.getClass().getSimpleName(),
                upstreamResp.headers().firstValue(GatewayHttpHeaderPolicy.HEADER_CONTENT_TYPE).orElse(""),
                sseDataLines, usageEventLines, doneSignalSeen, clientDisconnected, totalUsage.hasUsage(),
                totalUsage.getInputTokens(), totalUsage.getOutputTokens(),
                totalUsage.getCacheCreationTokens(), totalUsage.getCacheReadTokens());
        return new GatewayResponseResult(totalUsage, clientDisconnected, responseId);
    }

    private static boolean writeSseLine(PrintWriter writer, String line, boolean terminalLine) {
        writer.write(line);
        writer.write(terminalLine ? "\n\n" : "\n");
        writer.flush();
        return !writer.checkError();
    }

    private static void logClientDisconnected(GatewayRequestContext ctx, UsageTokens totalUsage) {
        log.warn("[{}] 客户端在流式响应期间断开，继续读取上游以收集 terminal usage: input={}, output={}, cache_w={}, cache_r={}",
                ctx != null ? ctx.getRequestId() : "?",
                totalUsage != null ? totalUsage.getInputTokens() : 0,
                totalUsage != null ? totalUsage.getOutputTokens() : 0,
                totalUsage != null ? totalUsage.getCacheCreationTokens() : 0,
                totalUsage != null ? totalUsage.getCacheReadTokens() : 0);
    }

    /** 将上游 SSE 聚合为客户端非流式响应，适配 OpenAI OAuth Codex 仅支持上游流式的场景。 */
    public GatewayResponseResult handleStreamingAsNonStreaming(HttpResponse<InputStream> upstreamResp,
                                                               HttpServletResponse response,
                                                               GatewayRequestContext ctx,
                                                               IUsageParser usageParser) throws IOException {
        response.setStatus(200);
        copyUpstreamResponseHeaders(upstreamResp, response, false);
        response.setContentType(GatewayResponseContentPolicy.MEDIA_TYPE_JSON);
        response.setCharacterEncoding(GatewayResponseContentPolicy.CHARSET_UTF_8);

        UsageTokens totalUsage = UsageTokens.builder().build();
        String responseModel = ctx != null && ctx.getRequestedModel() != null
                ? ctx.getRequestedModel()
                : OpenAiResponsesJsonPolicy.DEFAULT_MODEL;
        OpenAiResponsesSseAccumulator accumulator = new OpenAiResponsesSseAccumulator(responseModel);
        StringBuilder rawSseBody = new StringBuilder();
        String failedMessage = "";

        try (var upstreamInput = upstreamResp.body();
             var reader = new BufferedReader(new InputStreamReader(upstreamInput, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = restoreAnthropicToolNames(ctx, line);
                line = normalizeAnthropicUsageSseLine(ctx, ctx != null ? ctx.getUpstreamRoute() : null, line);
                rawSseBody.append(line).append('\n');
                mergeStreamingUsageFromUpstreamLine(totalUsage, usageParser, line);
                String dataPayload = OpenAiResponsesSsePolicy.extractDataPayload(line);
                if (dataPayload == null) continue;

                JsonNode event;
                try {
                    event = JSON_MAPPER.readTree(dataPayload);
                } catch (Exception e) {
                    continue;
                }
                if (OpenAiResponsesSsePolicy.EVENT_RESPONSE_FAILED.equals(
                        event.path(OpenAiResponsesJsonPolicy.FIELD_TYPE).asText(""))) {
                    failedMessage = extractUpstreamErrorMessage(event);
                    break;
                }
                if (accumulator.process(event)) {
                    break;
                }
            }
        }

        if (!failedMessage.isBlank()) {
            writeOpenAiProtocolError(response, failedMessage);
            return new GatewayResponseResult(totalUsage, false, accumulator.responseId());
        }

        String clientFormat = resolveClientFormat(ctx, ctx != null ? ctx.getUpstreamRoute() : null);
        if (!accumulator.terminalSeen()) {
            return switch (GatewayStreamAggregationPolicy.missingTerminalAction(clientFormat)) {
                case PRESERVE_UPSTREAM_SSE -> writePreservedSseResponse(upstreamResp, response, totalUsage, rawSseBody.toString());
                case PROTOCOL_ERROR -> {
                    writeOpenAiProtocolError(response, GatewayStreamAggregationPolicy.MISSING_TERMINAL_MESSAGE);
                    yield new GatewayResponseResult(totalUsage, false, accumulator.responseId());
                }
            };
        }
        if (GatewayStreamAggregationPolicy.isResponsesClient(clientFormat) && !accumulator.finalResponseSeen()) {
            return writePreservedSseResponse(upstreamResp, response, totalUsage, rawSseBody.toString());
        }
        if (!GatewayStreamAggregationPolicy.isResponsesClient(clientFormat) && !accumulator.terminalResponseSeen()) {
            writeOpenAiProtocolError(response, GatewayStreamAggregationPolicy.MISSING_TERMINAL_MESSAGE);
            return new GatewayResponseResult(totalUsage, false, accumulator.responseId());
        }

        String responsesBody = accumulator.buildResponsesJson(totalUsage);
        String clientBody = GatewayProtocolFormat.RESPONSES.is(clientFormat)
                ? responsesBody
                : translationService.translateResponse(responsesBody, GatewayProtocolFormat.RESPONSES.id(), clientFormat);
        try (var output = response.getOutputStream()) {
            output.write(clientBody.getBytes(StandardCharsets.UTF_8));
            output.flush();
        }

        log.debug("[{}] 流式聚合完成: has_usage={}, input={}, output={}, cache_w={}, cache_r={}",
                ctx != null ? ctx.getRequestId() : "?", totalUsage.hasUsage(),
                totalUsage.getInputTokens(), totalUsage.getOutputTokens(),
                totalUsage.getCacheCreationTokens(), totalUsage.getCacheReadTokens());
        return new GatewayResponseResult(totalUsage, false, accumulator.responseId());
    }

    private GatewayResponseResult writePreservedSseResponse(HttpResponse<InputStream> upstreamResp,
                                                            HttpServletResponse response,
                                                            UsageTokens usage,
                                                            String body) throws IOException {
        response.setStatus(upstreamResp.statusCode());
        response.setContentType(upstreamResp.headers().firstValue(GatewayHttpHeaderPolicy.HEADER_CONTENT_TYPE)
                .orElse(GatewayResponseContentPolicy.MEDIA_TYPE_EVENT_STREAM));
        response.setCharacterEncoding(GatewayResponseContentPolicy.CHARSET_UTF_8);
        try (var output = response.getOutputStream()) {
            output.write((body == null ? "" : body).getBytes(StandardCharsets.UTF_8));
            output.flush();
        }
        return new GatewayResponseResult(usage, false, "");
    }

    private static void writeOpenAiProtocolError(HttpServletResponse response, String message) throws IOException {
        response.setStatus(GatewayStreamAggregationPolicy.PROTOCOL_ERROR_STATUS);
        response.setContentType(GatewayResponseContentPolicy.MEDIA_TYPE_JSON);
        response.setCharacterEncoding(GatewayResponseContentPolicy.CHARSET_UTF_8);

        var root = JSON_MAPPER.createObjectNode();
        var error = JSON_MAPPER.createObjectNode();
        error.put(OpenAiResponsesJsonPolicy.FIELD_TYPE, GatewayStreamAggregationPolicy.PROTOCOL_ERROR_TYPE);
        error.put(OpenAiResponsesJsonPolicy.FIELD_MESSAGE, message == null || message.isBlank()
                ? GatewayStreamAggregationPolicy.INVALID_NON_STREAMING_RESPONSE_MESSAGE
                : message);
        root.set(OpenAiResponsesJsonPolicy.FIELD_ERROR, error);

        try (var output = response.getOutputStream()) {
            output.write(JSON_MAPPER.writeValueAsBytes(root));
            output.flush();
        }
    }

    private static String extractUpstreamErrorMessage(JsonNode event) {
        if (event == null) {
            return GatewayStreamAggregationPolicy.FAILED_TERMINAL_FALLBACK_MESSAGE;
        }
        String message = firstNonBlank(
                event.path(OpenAiResponsesJsonPolicy.FIELD_RESPONSE)
                        .path(OpenAiResponsesJsonPolicy.FIELD_ERROR)
                        .path(OpenAiResponsesJsonPolicy.FIELD_MESSAGE).asText(""),
                event.path(OpenAiResponsesJsonPolicy.FIELD_ERROR)
                        .path(OpenAiResponsesJsonPolicy.FIELD_MESSAGE).asText(""),
                event.path(OpenAiResponsesJsonPolicy.FIELD_MESSAGE).asText(""));
        return message.isBlank() ? GatewayStreamAggregationPolicy.FAILED_TERMINAL_FALLBACK_MESSAGE : message;
    }

    private static String resolveClientFormat(GatewayRequestContext ctx, UpstreamRoute route) {
        String format;
        if (route != null && route.clientFormat() != null) {
            format = route.clientFormat();
        } else if (ctx != null && ctx.getRequestFormat() != null) {
            format = ctx.getRequestFormat();
        } else {
            Platform requestPlatform = ctx != null ? ctx.getRequestPlatform() : null;
            format = ProtocolTranslationService.platformToFormatId(requestPlatform);
        }
        return ProtocolFormatResolver.normalizeFormat(format);
    }

    private static String resolveUpstreamFormat(GatewayRequestContext ctx, UpstreamRoute route) {
        String format;
        if (route != null) {
            format = route.upstreamFormat();
        } else if (ctx != null && ctx.getSelectedAccount() != null) {
            format = ProtocolTranslationService.platformToFormatId(ctx.getSelectedAccount().getPlatform());
        } else {
            format = null;
        }
        return ProtocolFormatResolver.normalizeFormat(format);
    }

    private static boolean isPassthrough(GatewayRequestContext ctx, UpstreamRoute route) {
        String clientFormat = resolveClientFormat(ctx, route);
        String upstreamFormat = resolveUpstreamFormat(ctx, route);
        return clientFormat != null && upstreamFormat != null
                && !clientFormat.isBlank()
                && clientFormat.equals(upstreamFormat);
    }

    private String normalizeAnthropicUsageBody(GatewayRequestContext ctx, UpstreamRoute route, String body) {
        return GatewayProtocolFormat.MESSAGES.is(resolveUpstreamFormat(ctx, route))
                ? anthropicUsageCompatibilityService.normalizeNonStreamingBody(body)
                : body;
    }

    private String normalizeAnthropicUsageSseLine(GatewayRequestContext ctx, UpstreamRoute route, String line) {
        return GatewayProtocolFormat.MESSAGES.is(resolveUpstreamFormat(ctx, route))
                ? anthropicUsageCompatibilityService.normalizeSseLine(line)
                : line;
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
            if (!GatewayResponseHeaderPolicy.shouldCopy(name, passthrough)) {
                continue;
            }
            for (String value : entry.getValue()) {
                if (value != null && !value.isBlank()) {
                    response.addHeader(name, value);
                }
            }
        }
    }

    public GatewayResponseResult handleNonStreaming(HttpResponse<InputStream> upstreamResp,
                                                    HttpServletResponse response,
                                                    IUsageParser usageParser) throws IOException {
        GatewayRequestContext ctx = GatewayRequestContext.get();
        UpstreamRoute route = ctx != null ? ctx.getUpstreamRoute() : null;
        boolean passthrough = isPassthrough(ctx, route);
        response.setStatus(upstreamResp.statusCode());
        copyUpstreamResponseHeaders(upstreamResp, response, passthrough);
        response.setContentType(passthrough
                ? upstreamResp.headers().firstValue(GatewayHttpHeaderPolicy.HEADER_CONTENT_TYPE).orElse(GatewayResponseContentPolicy.MEDIA_TYPE_JSON)
                : GatewayResponseContentPolicy.MEDIA_TYPE_JSON);
        response.setCharacterEncoding(GatewayResponseContentPolicy.CHARSET_UTF_8);

        String responseBody;
        try (var input = upstreamResp.body()) {
            responseBody = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        responseBody = restoreAnthropicToolNames(ctx, responseBody);
        responseBody = normalizeAnthropicUsageBody(ctx, route, responseBody);

        // 先解析用量（用上游格式），再做响应协议翻译
        UsageTokens usage = usageParser.parseNonStreaming(responseBody);
        var cacheTtlOverrideResult = cacheTtlUsageOverrideService.applyToNonStreamingBody(
                responseBody, usage, ctx != null ? ctx.getSelectedAccount() : null);
        responseBody = cacheTtlOverrideResult.body();

        // 协议翻译：上游格式 → 客户端格式。
        // 优先使用 UpstreamRoute 中的格式，保证与请求翻译、usage parser 的路由决策一致。
        log.debug("[{}] 非流式用量解析: parser={}, body_bytes={}, has_usage={}, input={}, output={}, cache_w={}, cache_r={}",
                ctx != null ? ctx.getRequestId() : "?",
                usageParser.getClass().getSimpleName(),
                responseBody.getBytes(StandardCharsets.UTF_8).length,
                usage != null && usage.hasUsage(),
                usage != null ? usage.getInputTokens() : 0,
                usage != null ? usage.getOutputTokens() : 0,
                usage != null ? usage.getCacheCreationTokens() : 0,
                usage != null ? usage.getCacheReadTokens() : 0);
        String clientFormat = resolveClientFormat(ctx, route);
        String upstreamFormat = resolveUpstreamFormat(ctx, route);
        boolean needRespTranslation = clientFormat != null && upstreamFormat != null
                && !clientFormat.equals(upstreamFormat);
        String clientBody = responseBody;
        if (needRespTranslation) {
            log.debug("[{}] 响应协议翻译: {} -> {}",
                    ctx != null ? ctx.getRequestId() : "?", upstreamFormat, clientFormat);
            clientBody = translationService.translateResponse(responseBody, upstreamFormat, clientFormat);
        }

        try (var output = response.getOutputStream()) {
            output.write(clientBody.getBytes(StandardCharsets.UTF_8));
            output.flush();
        }

        return new GatewayResponseResult(usage, false, extractResponseIdFromJson(responseBody));
    }

    private static String extractResponseIdFromSseLine(String line) {
        String dataPayload = OpenAiResponsesSsePolicy.extractDataPayload(line);
        if (dataPayload == null) return "";
        try {
            JsonNode root = JSON_MAPPER.readTree(dataPayload);
            return firstNonBlank(
                    root.path(OpenAiResponsesJsonPolicy.FIELD_RESPONSE)
                            .path(OpenAiResponsesJsonPolicy.FIELD_ID).asText(""),
                    root.path(OpenAiResponsesJsonPolicy.FIELD_ID).asText(""));
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String extractResponseIdFromJson(String body) {
        if (body == null || body.isBlank()) return "";
        try {
            JsonNode root = JSON_MAPPER.readTree(body);
            return firstNonBlank(
                    root.path(OpenAiResponsesJsonPolicy.FIELD_ID).asText(""),
                    root.path(OpenAiResponsesJsonPolicy.FIELD_RESPONSE)
                            .path(OpenAiResponsesJsonPolicy.FIELD_ID).asText(""));
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return "";
        for (String value : values) {
            if (value != null && !value.isBlank()) return value.trim();
        }
        return "";
    }

    public void writePassthroughError(HttpResponse<InputStream> upstreamResp,
                                      HttpServletResponse response,
                                      String responseBody) throws IOException {
        response.setStatus(upstreamResp.statusCode());
        copyUpstreamResponseHeaders(upstreamResp, response, true);
        response.setContentType(upstreamResp.headers().firstValue(GatewayHttpHeaderPolicy.HEADER_CONTENT_TYPE)
                .orElse(GatewayResponseContentPolicy.MEDIA_TYPE_JSON));
        response.setCharacterEncoding(GatewayResponseContentPolicy.CHARSET_UTF_8);
        try (var output = response.getOutputStream()) {
            GatewayRequestContext ctx = GatewayRequestContext.get();
            output.write(restoreAnthropicToolNames(ctx, responseBody == null ? "" : responseBody)
                    .getBytes(StandardCharsets.UTF_8));
            output.flush();
        }
    }

    /** 构造 Anthropic Messages 非流式响应 JSON。 */
    private String buildAnthropicMessageJson(String id, String model, String text, String stopReason, UsageTokens usage)
            throws IOException {
        var root = JSON_MAPPER.createObjectNode();
        root.put(AnthropicMessagesBodyPolicy.FIELD_ID, id);
        root.put(AnthropicMessagesBodyPolicy.FIELD_TYPE, AnthropicMessagesBodyPolicy.TYPE_MESSAGE);
        root.put(AnthropicMessagesBodyPolicy.FIELD_ROLE, AnthropicMessagesBodyPolicy.ROLE_ASSISTANT);
        root.put(AnthropicMessagesBodyPolicy.FIELD_MODEL, model);
        var content = JSON_MAPPER.createArrayNode();
        var textBlock = JSON_MAPPER.createObjectNode();
        textBlock.put(AnthropicMessagesBodyPolicy.FIELD_TYPE, AnthropicMessagesBodyPolicy.TYPE_TEXT);
        textBlock.put(AnthropicMessagesBodyPolicy.FIELD_TEXT, text);
        content.add(textBlock);
        root.set(AnthropicMessagesBodyPolicy.FIELD_CONTENT, content);
        root.put(AnthropicMessagesBodyPolicy.FIELD_STOP_REASON, stopReason);
        root.putNull(AnthropicMessagesBodyPolicy.FIELD_STOP_SEQUENCE);
        var usageNode = JSON_MAPPER.createObjectNode();
        usageNode.put(AnthropicMessagesBodyPolicy.FIELD_INPUT_TOKENS,
                usage != null ? usage.getInputTokens() : 0);
        usageNode.put(AnthropicMessagesBodyPolicy.FIELD_OUTPUT_TOKENS,
                usage != null ? usage.getOutputTokens() : 0);
        if (usage != null && usage.getCacheCreationTokens() > 0) {
            usageNode.put(AnthropicMessagesBodyPolicy.FIELD_CACHE_CREATION_INPUT_TOKENS,
                    usage.getCacheCreationTokens());
        }
        if (usage != null && usage.getCacheReadTokens() > 0) {
            usageNode.put(AnthropicMessagesBodyPolicy.FIELD_CACHE_READ_INPUT_TOKENS,
                    usage.getCacheReadTokens());
        }
        root.set(AnthropicMessagesBodyPolicy.FIELD_USAGE, usageNode);
        return JSON_MAPPER.writeValueAsString(root);
    }

    /** 从上游原始 SSE 行解析用量，避免协议翻译路径丢失缓存 Token。 */
    private boolean mergeStreamingUsageFromUpstreamLine(UsageTokens totalUsage,
                                                        IUsageParser usageParser,
                                                        String line) {
        if (totalUsage == null || usageParser == null || line == null) return false;
        String dataPayload = OpenAiResponsesSsePolicy.extractDataPayload(line);
        if (dataPayload == null) return false;

        UsageTokens eventUsage = usageParser.parseSSELine(dataPayload);
        if (eventUsage != null) {
            totalUsage.merge(eventUsage);
            return eventUsage.hasUsage();
        }
        return false;
    }

    private static String restoreAnthropicToolNames(GatewayRequestContext ctx, String data) {
        if (ctx == null || data == null || !ctx.isShouldMimicClaudeCode()
                || ctx.getAnthropicToolNameRewrite() == null) {
            return data;
        }
        return ctx.getAnthropicToolNameRewrite().restore(data);
    }
}
