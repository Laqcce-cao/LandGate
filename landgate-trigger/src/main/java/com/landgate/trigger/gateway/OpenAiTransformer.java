package com.landgate.trigger.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.landgate.domain.account.model.entity.AccountEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * OpenAI 协议请求转换器 —— 构建转发至 OpenAI API 的上游请求。
 * <p>
 * 负责：设置认证头 → 配置代理 → 处理模型映射。
 * <p>
 * 上游路径选择基于客户端请求格式（{@link GatewayRequestContext#getRequestFormat()}）：
 * <ul>
 *   <li>{@code "responses"} → {@code /v1/responses}</li>
 *   <li>其他（含 {@code "chat_completions"}、{@code "messages"}） → {@code /v1/chat/completions}</li>
 * </ul>
 * Phase 2 删除 {@code Platform.OPENAI_RESPONSES} 枚举后，端点选择不再依赖 Platform，
 * 由客户端实际请求的 URL 路径决定（与 Sub2API 行为对齐）。
 */
@Slf4j
@Component
public class OpenAiTransformer implements IRequestTransformer {

    private static final String OPENAI_CHAT_URL = "https://api.openai.com/v1/chat/completions";
    private static final String OPENAI_RESPONSES_URL = "https://api.openai.com/v1/responses";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    public HttpRequest buildUpstreamRequest(String body, AccountEntity account, String accessToken) {
        // 根据当前请求上下文中的 requestFormat 选择上游路径
        GatewayRequestContext ctx = GatewayRequestContext.get();
        boolean isResponses = ctx != null && "responses".equals(ctx.getRequestFormat());
        String pathSuffix = isResponses ? "/v1/responses" : "/v1/chat/completions";

        String targetUrl = isResponses ? OPENAI_RESPONSES_URL : OPENAI_CHAT_URL;

        if (account.getExtra() != null && !account.getExtra().equals("{}")) {
            try {
                var extra = JSON.readTree(account.getExtra());
                if (extra.has("base_url") && !extra.get("base_url").asText().isEmpty()) {
                    targetUrl = extra.get("base_url").asText() + pathSuffix;
                }
            } catch (Exception e) {
                log.warn("Failed to parse base_url for OpenAI account: account_id={}", account.getId());
            }
        }

        var headers = new ArrayList<String>();
        headers.addAll(List.of("Authorization", "Bearer " + accessToken));
        headers.addAll(List.of("Content-Type", "application/json"));

        return HttpRequest.newBuilder()
                .uri(URI.create(targetUrl))
                .timeout(Duration.ofSeconds(120))
                .headers(headers.toArray(new String[0]))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
    }

    @Override
    public String extractUserId(String body) {
        try {
            JsonNode root = JSON.readTree(body);
            if (root.has("user")) {
                JsonNode userNode = root.get("user");
                if (userNode.isTextual()) return userNode.asText();
            }
        } catch (Exception e) {
            log.debug("Failed to extract user from OpenAI request body");
        }
        return null;
    }

    @Override
    public String extractModel(String body) {
        try {
            JsonNode root = JSON.readTree(body);
            if (root.has("model")) return root.get("model").asText();
        } catch (Exception e) {
            log.debug("Failed to extract model from OpenAI request body");
        }
        return "unknown";
    }

    @Override
    public boolean isStreamRequest(String body) {
        try {
            JsonNode root = JSON.readTree(body);
            if (root.has("stream")) return root.get("stream").asBoolean();
        } catch (Exception e) {
            // ignore
        }
        return false;
    }
}

