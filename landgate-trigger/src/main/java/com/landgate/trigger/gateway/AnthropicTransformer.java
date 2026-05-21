package com.landgate.trigger.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.landgate.domain.account.model.entity.AccountEntity;
import com.landgate.types.enums.AccountType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Anthropic 协议请求转换器 —— 构建转发至 Anthropic API 的上游请求。
 * <p>
 * 负责：设置 API 版本头 → 注入认证 Token → 配置代理 → 检查模型过滤。
 */
@Slf4j
@Component
public class AnthropicTransformer implements IRequestTransformer {

    private static final String ANTHROPIC_API_URL = "https://api.anthropic.com/v1/messages";
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final ObjectMapper JSON = new ObjectMapper();

    public HttpRequest buildUpstreamRequest(String body, AccountEntity account, String accessToken) {
        String modelName = extractModel(body);
        String targetUrl = ANTHROPIC_API_URL;
        if (account.getExtra() != null && !account.getExtra().equals("{}")) {
            try {
                JsonNode extra = JSON.readTree(account.getExtra());
                if (extra.has("base_url") && !extra.get("base_url").asText().isEmpty()) {
                    targetUrl = extra.get("base_url").asText() + "/v1/messages";
                }
            } catch (Exception e) {
                log.warn("Failed to parse account extra for base_url: account_id={}", account.getId());
            }
        }

        var headers = buildHeaders(account, accessToken);
        log.debug("Building upstream request: url={}, model={}, account_id={}", targetUrl, modelName, account.getId());

        return HttpRequest.newBuilder()
                .uri(URI.create(targetUrl))
                .timeout(Duration.ofSeconds(120))
                .header("Content-Type", "application/json")
                .headers(headers)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
    }

    private String[] buildHeaders(AccountEntity account, String accessToken) {
        var headers = new ArrayList<String>();
        if (account.getType() == AccountType.OAUTH || account.getType() == AccountType.SETUP_TOKEN) {
            headers.addAll(List.of("Authorization", "Bearer " + accessToken));
        } else {
            headers.addAll(List.of("x-api-key", accessToken));
        }
        headers.addAll(List.of("anthropic-version", ANTHROPIC_VERSION));
        if (account.getType() == AccountType.OAUTH || account.getType() == AccountType.SETUP_TOKEN) {
            headers.addAll(List.of("anthropic-beta", "oauth-2025-04-20"));
        }
        return headers.toArray(new String[0]);
    }

    @Override
    public String extractUserId(String body) {
        try {
            JsonNode root = JSON.readTree(body);
            if (root.has("metadata") && root.get("metadata").has("user_id")) {
                return root.get("metadata").get("user_id").asText();
            }
        } catch (Exception e) {
            log.debug("Failed to extract user_id from Anthropic request body");
        }
        return null;
    }

    public String extractModel(String body) {
        try {
            JsonNode root = JSON.readTree(body);
            if (root.has("model")) return root.get("model").asText();
        } catch (Exception e) {
            log.debug("Failed to extract model from request body");
        }
        return "unknown";
    }

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
