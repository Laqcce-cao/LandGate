package com.landgate.trigger.gateway.route;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.landgate.trigger.gateway.converter.ProtocolFormatResolver;
import com.landgate.types.enums.Platform;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Anthropic 路由策略 —— 所有 Anthropic 账号统一发送到 Messages 端点。
 */
@Component
@Order(30)
public class AnthropicRouteStrategy implements UpstreamRouteStrategy {

    private static final String ANTHROPIC_API_URL = "https://api.anthropic.com/v1/messages";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    public boolean supports(UpstreamRouteRequest request) {
        return request != null
                && request.account() != null
                && request.account().getPlatform() == Platform.ANTHROPIC;
    }

    @Override
    public UpstreamRoute resolve(UpstreamRouteRequest request) {
        String upstreamFormat = ProtocolFormatResolver.requireSingleAccountUpstreamFormat(
                request.account(), java.util.Set.of("messages"));
        return new UpstreamRoute(
                Platform.ANTHROPIC,
                request.requestFormat(),
                upstreamFormat,
                EndpointKind.ANTHROPIC_MESSAGES,
                resolveTargetUrl(request),
                false,
                false,
                upstreamFormat,
                "anthropic_messages"
        );
    }

    /** 根据账号 base_url 覆盖 Anthropic Messages 地址。 */
    private String resolveTargetUrl(UpstreamRouteRequest request) {
        String extra = request.account().getExtra();
        if (extra == null || extra.equals("{}")) return ANTHROPIC_API_URL;
        try {
            JsonNode root = JSON.readTree(extra);
            if (root.has("base_url") && !root.get("base_url").asText().isEmpty()) {
                return root.get("base_url").asText() + "/v1/messages";
            }
        } catch (Exception ignored) {
            // extra 解析失败时保持默认 Anthropic 地址。
        }
        return ANTHROPIC_API_URL;
    }
}
