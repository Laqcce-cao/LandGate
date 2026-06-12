package com.landgate.trigger.gateway.route;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.landgate.trigger.gateway.ProtocolFormatResolver;
import com.landgate.types.enums.Platform;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Gemini 路由策略 —— 使用请求路径构建 Gemini generateContent 上游地址。
 */
@Component
@Order(50)
public class GeminiRouteStrategy implements UpstreamRouteStrategy {

    private static final String GEMINI_API_BASE = "https://generativelanguage.googleapis.com";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    public boolean supports(UpstreamRouteRequest request) {
        return request != null
                && request.account() != null
                && request.account().getPlatform() == Platform.GEMINI;
    }

    @Override
    public UpstreamRoute resolve(UpstreamRouteRequest request) {
        String upstreamFormat = ProtocolFormatResolver.resolveAccountUpstreamFormat(
                request.account(), "gemini", java.util.Set.of("gemini"));
        boolean passthrough = upstreamFormat.equals(ProtocolFormatResolver.normalizeFormat(request.requestFormat()));
        return new UpstreamRoute(
                Platform.GEMINI,
                request.requestFormat(),
                upstreamFormat,
                EndpointKind.GEMINI_GENERATE_CONTENT,
                resolveTargetUrl(request),
                passthrough,
                false,
                false,
                upstreamFormat,
                "gemini_generate_content"
        );
    }

    /** 根据账号 base_url 和请求路径构建 Gemini 上游地址，不在此处附加 API Key。 */
    private String resolveTargetUrl(UpstreamRouteRequest request) {
        String baseUrl = GEMINI_API_BASE;
        String extra = request.account().getExtra();
        if (extra != null && !extra.equals("{}")) {
            try {
                JsonNode root = JSON.readTree(extra);
                if (root.has("base_url") && !root.get("base_url").asText().isEmpty()) {
                    baseUrl = root.get("base_url").asText();
                }
            } catch (Exception ignored) {
                // extra 解析失败时保持默认 Gemini 地址。
            }
        }
        String path = request.upstreamPath() != null
                ? request.upstreamPath()
                : "/v1beta/models/" + request.requestedModel() + ":generateContent";
        return baseUrl + path;
    }
}
