package com.landgate.trigger.gateway.route;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.landgate.trigger.gateway.ProtocolFormatResolver;
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
    private static final String OPENAI_BASE_URL = "https://api.openai.com";
    private static final String RESPONSES_PATH = "/v1/responses";
    private static final String CODEX_RESPONSES_PATH = "/backend-api/codex/responses";
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
        String configuredUpstreamFormat = ProtocolFormatResolver.resolveAccountUpstreamFormat(request.account(), "");
        boolean useResponses = switch (configuredUpstreamFormat) {
            case "responses" -> true;
            case "chat_completions" -> false;
            default -> "responses".equals(request.requestFormat())
                    && upstreamCapabilityService.shouldUseResponsesAPI(request.account());
        };
        String upstreamFormat = useResponses ? "responses" : "chat_completions";
        EndpointKind endpointKind = useResponses
                ? EndpointKind.OPENAI_RESPONSES
                : EndpointKind.OPENAI_CHAT_COMPLETIONS;
        String pathSuffix = useResponses ? resolveResponsesPathSuffix(request) : "/v1/chat/completions";
        String defaultUrl = useResponses ? OPENAI_BASE_URL + pathSuffix : OPENAI_CHAT_URL;
        boolean passthrough = upstreamFormat.equals(ProtocolFormatResolver.normalizeFormat(request.requestFormat()));

        return new UpstreamRoute(
                Platform.OPENAI,
                request.requestFormat(),
                upstreamFormat,
                endpointKind,
                resolveTargetUrl(request, defaultUrl, pathSuffix),
                passthrough,
                false,
                false,
                upstreamFormat,
                useResponses ? "openai_api_key_responses" : "openai_api_key_chat_completions"
        );
    }

    private static String resolveResponsesPathSuffix(UpstreamRouteRequest request) {
        String path = request.upstreamPath();
        if (path == null || path.isBlank()) {
            return RESPONSES_PATH;
        }
        if (path.startsWith(RESPONSES_PATH)) {
            return path;
        }
        if (path.startsWith("/responses")) {
            return "/v1" + path;
        }
        if (path.startsWith(CODEX_RESPONSES_PATH)) {
            return RESPONSES_PATH + path.substring(CODEX_RESPONSES_PATH.length());
        }
        return RESPONSES_PATH;
    }

    /** 根据账号 base_url 覆盖最终目标地址。 */
    private String resolveTargetUrl(UpstreamRouteRequest request, String defaultUrl, String pathSuffix) {
        String extra = request.account().getExtra();
        if (extra == null || extra.equals("{}")) return defaultUrl;
        try {
            JsonNode root = JSON.readTree(extra);
            if (root.has("base_url") && !root.get("base_url").asText().isEmpty()) {
                return buildOpenAiEndpointUrl(root.get("base_url").asText(), pathSuffix);
            }
        } catch (Exception ignored) {
            // extra 解析失败时保持默认 OpenAI 地址，与现有 Transformer 行为一致。
        }
        return defaultUrl;
    }

    static String buildOpenAiEndpointUrl(String baseUrl, String pathSuffix) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return OPENAI_BASE_URL + pathSuffix;
        }
        String base = baseUrl.trim();
        while (base.endsWith("/") && base.length() > 1) {
            base = base.substring(0, base.length() - 1);
        }
        String suffix = pathSuffix == null || pathSuffix.isBlank() ? RESPONSES_PATH : pathSuffix;
        if (!suffix.startsWith("/")) {
            suffix = "/" + suffix;
        }

        if (suffix.startsWith("/v1/chat/completions")) {
            if (base.endsWith("/v1/chat/completions") || base.endsWith("/chat/completions")) {
                return base + suffix.substring("/v1/chat/completions".length());
            }
            if (base.endsWith("/v1")) {
                return base + suffix.substring("/v1".length());
            }
            return base + suffix;
        }

        if (suffix.startsWith(RESPONSES_PATH)) {
            if (base.endsWith(RESPONSES_PATH) || base.endsWith("/responses")) {
                return base + suffix.substring(RESPONSES_PATH.length());
            }
            if (base.endsWith("/v1")) {
                return base + suffix.substring("/v1".length());
            }
            return base + suffix;
        }

        return base + suffix;
    }
}
