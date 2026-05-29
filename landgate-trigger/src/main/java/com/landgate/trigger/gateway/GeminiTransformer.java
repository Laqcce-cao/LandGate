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

/**
 * Gemini 协议请求转换器 —— 构建转发至 Google Gemini API 的上游请求。
 * <p>
 * Gemini 模型名来自 URL 路径变量（非请求体），通过 {@link GatewayRequestContext#getUpstreamPath()}
 * 获取完整 servlet path 来构建上游 URL。
 */
@Slf4j
@Component
public class GeminiTransformer implements IRequestTransformer {

    private static final String GEMINI_API_BASE = "https://generativelanguage.googleapis.com";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    public HttpRequest buildUpstreamRequest(String body, AccountEntity account, String accessToken) {
        GatewayRequestContext ctx = GatewayRequestContext.get();
        String targetUrl = resolveTargetUrl(account, ctx);
        String upstreamUrl = targetUrl + (targetUrl.contains("?") ? "&" : "?") + "key=" + accessToken;

        log.debug("Gemini upstream URL: {}", upstreamUrl.substring(0, Math.min(200, upstreamUrl.length())));

        return HttpRequest.newBuilder()
                .uri(URI.create(upstreamUrl))
                .timeout(Duration.ofSeconds(120))
                .header("Content-Type", "application/json")
                .POST(body != null
                        ? HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)
                        : HttpRequest.BodyPublishers.noBody())
                .build();
    }

    /** 解析上游目标地址，正常网关路径优先使用策略路由结果。 */
    private String resolveTargetUrl(AccountEntity account, GatewayRequestContext ctx) {
        if (ctx != null && ctx.getUpstreamRoute() != null && ctx.getUpstreamRoute().targetUrl() != null) {
            return ctx.getUpstreamRoute().targetUrl();
        }
        if (ctx != null && ctx.getUpstreamPath() != null) {
            String baseUrl = GEMINI_API_BASE;
            if (account.getExtra() != null && !account.getExtra().equals("{}")) {
                try {
                    JsonNode extra = JSON.readTree(account.getExtra());
                    if (extra.has("base_url") && !extra.get("base_url").asText().isEmpty()) {
                        baseUrl = extra.get("base_url").asText();
                    }
                } catch (Exception e) {
                    log.warn("Failed to parse base_url for Gemini account: account_id={}", account.getId());
                }
            }
            return baseUrl + ctx.getUpstreamPath();
        }
        String modelPath = ctx != null ? ctx.getRequestedModel() : "unknown";
        return GEMINI_API_BASE + "/v1beta/models/" + modelPath + ":generateContent";
    }

    @Override
    public String extractModel(String body) {
        // Gemini 模型名来自 URL path，不从 body 提取
        GatewayRequestContext ctx = GatewayRequestContext.get();
        if (ctx != null && ctx.getRequestedModel() != null) {
            return ctx.getRequestedModel();
        }
        return "gemini";
    }

    @Override
    public boolean isStreamRequest(String body) {
        // Gemini 流式暂不支持
        return false;
    }
}
