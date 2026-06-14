package com.landgate.trigger.gateway.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.landgate.trigger.gateway.GatewayDispatcher;
import com.landgate.trigger.gateway.oauth.ClaudeCodeDetector;
import com.landgate.types.enums.Platform;
import com.landgate.types.gateway.AnthropicMessagesBodyPolicy;
import com.landgate.types.gateway.GatewayHttpHeaderPolicy;
import com.landgate.types.gateway.GatewayProtocolFormat;
import com.landgate.types.gateway.GatewayRequestBodyPolicy;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * Detects client request format and Claude Code client traits.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClientProfileService {

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    private final ClaudeCodeDetector claudeCodeDetector;

    public ClientProfile detect(String body, HttpServletRequest request) {
        Platform requestPlatform = (Platform) request.getAttribute(GatewayDispatcher.ATTR_REQUEST_PLATFORM);
        String requestFormat = (String) request.getAttribute(GatewayDispatcher.ATTR_REQUEST_FORMAT);
        Map<String, String> headers = extractHeadersMap(request);

        boolean isClaudeCode = false;
        String metadataUserId = null;

        // 仅 Anthropic 端点检测 Claude Code
        if (requestPlatform == Platform.ANTHROPIC) {
            metadataUserId = extractMetadataUserIdFromBody(body);
            // /v1/messages 端点: 完整校验（UA + system prompt 相似度 + 必要 header）
            // 非 messages 端点: UA 匹配 claude-cli/* 即视为 true（与 Sub2API gateway_helper.go:42-44 一致）
            if (GatewayProtocolFormat.MESSAGES.is(requestFormat)) {
                String systemPrompt = ClaudeCodeDetector.extractSystemPrompt(body);
                isClaudeCode = claudeCodeDetector.validateForMessages(
                        request.getHeader(GatewayHttpHeaderPolicy.HEADER_USER_AGENT), metadataUserId,
                        systemPrompt,
                        extractMaxTokens(body), extractModel(body),
                        headers);
            } else {
                isClaudeCode = claudeCodeDetector.validateForNonMessages(
                        request.getHeader(GatewayHttpHeaderPolicy.HEADER_USER_AGENT));
            }
        }

        return new ClientProfile(requestPlatform, requestFormat, isClaudeCode, metadataUserId, headers);
    }

    /** 从请求 body JSON 中提取 model 字段 */
    private static String extractModel(String body) {
        try {
            JsonNode root = JSON_MAPPER.readTree(body);
            if (root.has(GatewayRequestBodyPolicy.FIELD_MODEL)) {
                return root.get(GatewayRequestBodyPolicy.FIELD_MODEL).asText();
            }
        } catch (Exception e) {
            // ignore
        }
        return GatewayRequestBodyPolicy.DEFAULT_MODEL;
    }

    /** 从请求 body 中提取 metadata.user_id */
    private static String extractMetadataUserIdFromBody(String body) {
        try {
            JsonNode root = JSON_MAPPER.readTree(body);
            if (root.has("metadata") && root.get("metadata").has("user_id")) {
                return root.get("metadata").get("user_id").asText();
            }
        } catch (Exception e) {
            // ignore
        }
        return null;
    }

    /** 从请求 body 中提取 max_tokens */
    private static int extractMaxTokens(String body) {
        try {
            JsonNode root = JSON_MAPPER.readTree(body);
            if (root.has(AnthropicMessagesBodyPolicy.FIELD_MAX_TOKENS)) {
                return root.get(AnthropicMessagesBodyPolicy.FIELD_MAX_TOKENS).asInt();
            }
        } catch (Exception e) {
            // ignore
        }
        return 0;
    }

    /** 将 HttpServletRequest 的 header 提取为 Map */
    private static Map<String, String> extractHeadersMap(HttpServletRequest request) {
        Map<String, String> headers = new HashMap<>();
        var headerNames = request.getHeaderNames();
        while (headerNames.hasMoreElements()) {
            String name = headerNames.nextElement();
            headers.put(name, request.getHeader(name));
        }
        return headers;
    }
}
