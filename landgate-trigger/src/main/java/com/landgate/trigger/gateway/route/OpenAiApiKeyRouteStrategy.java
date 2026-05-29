package com.landgate.trigger.gateway.route;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.landgate.trigger.gateway.UpstreamCapabilityService;
import com.landgate.types.enums.AccountType;
import com.landgate.types.enums.Platform;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * OpenAI API Key 路由策略 —— 根据客户端格式和 Responses 能力选择 Responses 或 Chat Completions 端点。
 */
@Component
@Order(20)
public class OpenAiApiKeyRouteStrategy implements UpstreamRouteStrategy {

    private static final String OPENAI_CHAT_URL = "https://api.openai.com/v1/chat/completions";
    private static final String OPENAI_RESPONSES_URL = "https://api.openai.com/v1/responses";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final UpstreamCapabilityService upstreamCapabilityService;

    public OpenAiApiKeyRouteStrategy(UpstreamCapabilityService upstreamCapabilityService) {
        this.upstreamCapabilityService = upstreamCapabilityService;
    }

    @Override
    public boolean supports(UpstreamRouteRequest request) {
        return request != null
                && request.account() != null
                && request.account().getPlatform() == Platform.OPENAI
                && request.account().getType() == AccountType.API_KEY;
    }

    @Override
    public UpstreamRoute resolve(UpstreamRouteRequest request) {
        boolean useResponses = "responses".equals(request.requestFormat())
                && upstreamCapabilityService.shouldUseResponsesAPI(request.account());
        String upstreamFormat = useResponses ? "responses" : "chat_completions";
        EndpointKind endpointKind = useResponses
                ? EndpointKind.OPENAI_RESPONSES
                : EndpointKind.OPENAI_CHAT_COMPLETIONS;
        String defaultUrl = useResponses ? OPENAI_RESPONSES_URL : OPENAI_CHAT_URL;
        String pathSuffix = useResponses ? "/v1/responses" : "/v1/chat/completions";

        return new UpstreamRoute(
                Platform.OPENAI,
                request.requestFormat(),
                upstreamFormat,
                endpointKind,
                resolveTargetUrl(request, defaultUrl, pathSuffix),
                upstreamCapabilityService.isPassthroughEnabled(request.account()),
                useResponses,
                false,
                upstreamFormat,
                useResponses ? "openai_api_key_responses" : "openai_api_key_chat_completions"
        );
    }

    /** 根据账号 base_url 覆盖最终目标地址。 */
    private String resolveTargetUrl(UpstreamRouteRequest request, String defaultUrl, String pathSuffix) {
        String extra = request.account().getExtra();
        if (extra == null || extra.equals("{}")) return defaultUrl;
        try {
            JsonNode root = JSON.readTree(extra);
            if (root.has("base_url") && !root.get("base_url").asText().isEmpty()) {
                return root.get("base_url").asText() + pathSuffix;
            }
        } catch (Exception ignored) {
            // extra 解析失败时保持默认 OpenAI 地址，与现有 Transformer 行为一致。
        }
        return defaultUrl;
    }
}
