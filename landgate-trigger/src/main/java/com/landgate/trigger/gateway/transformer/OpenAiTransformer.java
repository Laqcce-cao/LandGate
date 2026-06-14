package com.landgate.trigger.gateway.transformer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.landgate.domain.account.model.entity.AccountEntity;
import com.landgate.trigger.gateway.GatewayRequestContext;
import com.landgate.trigger.gateway.route.EndpointKind;
import com.landgate.trigger.gateway.route.UpstreamEndpointDefaults;
import com.landgate.trigger.gateway.route.UpstreamRoute;
import com.landgate.types.enums.AccountType;
import com.landgate.types.gateway.GatewayRequestBodyPolicy;
import com.landgate.types.gateway.OpenAiResponsesBodyPolicy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * OpenAI 协议请求转换器 —— 根据已解析的 {@link UpstreamRoute} 构建 OpenAI 上游 HTTP 请求。
 * <p>
 * 负责：选择上游 URL、调用端点专用请求体 normalizer、设置认证头、超时和请求体。
 * Endpoint 路由由 route strategy 完成；Codex body mutation 由 {@link OpenAiCodexBodyNormalizer} 完成。
 * 无 {@link GatewayRequestContext} 的直接调用仅保留兼容 fallback，不作为正常网关路由路径。
 */
@Slf4j
@Component
public class OpenAiTransformer implements IRequestTransformer {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Value("${landgate.gateway.codex.preserve-prompt-cache-key:true}")
    private boolean preservePromptCacheKey = true;

    private final OpenAiResponsesRequestNormalizer responsesRequestNormalizer;
    private final OpenAiChatCompletionsRequestNormalizer chatCompletionsRequestNormalizer;
    private final OpenAiCodexBodyNormalizer codexBodyNormalizer;

    public OpenAiTransformer() {
        this(new OpenAiResponsesRequestNormalizer(),
                new OpenAiChatCompletionsRequestNormalizer(),
                new OpenAiCodexBodyNormalizer());
    }

    public OpenAiTransformer(OpenAiResponsesRequestNormalizer responsesRequestNormalizer) {
        this(responsesRequestNormalizer,
                new OpenAiChatCompletionsRequestNormalizer(),
                new OpenAiCodexBodyNormalizer());
    }

    @Autowired
    public OpenAiTransformer(OpenAiResponsesRequestNormalizer responsesRequestNormalizer,
                             OpenAiChatCompletionsRequestNormalizer chatCompletionsRequestNormalizer,
                             OpenAiCodexBodyNormalizer codexBodyNormalizer) {
        this.responsesRequestNormalizer = responsesRequestNormalizer;
        this.chatCompletionsRequestNormalizer = chatCompletionsRequestNormalizer;
        this.codexBodyNormalizer = codexBodyNormalizer;
    }

    @Override
    public HttpRequest buildUpstreamRequest(UpstreamRequestContext context) {
        String body = context.body();
        AccountEntity account = context.account();
        String targetUrl;
        boolean isOAuth = account.getType() == AccountType.OAUTH;
        UpstreamRoute route = context.upstreamRoute();

        if (route != null) {
            targetUrl = route.targetUrl();
            if (route.normalizeCodexOAuthBody()) {
                body = normalizeCodexOAuthRequestBody(body, account, route, context.requestId(), context.requestedModel());
            } else if (route.endpointKind() == EndpointKind.OPENAI_RESPONSES) {
                body = responsesRequestNormalizer.normalize(body, route, account);
            } else if (route.endpointKind() == EndpointKind.OPENAI_CHAT_COMPLETIONS) {
                body = chatCompletionsRequestNormalizer.normalize(body, account);
            }
        } else if (isOAuth) {
            // 兼容无 GatewayRequestContext 的单元调用；正常网关路径由 UpstreamRoute 决定端点。
            targetUrl = UpstreamEndpointDefaults.openAiCodexResponsesUrl();
            body = normalizeCodexOAuthRequestBody(body, account);
        } else {
            targetUrl = UpstreamEndpointDefaults.openAiChatCompletionsUrl();
        }

        log.debug("OpenAI upstream URL: url={}, account_id={}, isOAuth={}", targetUrl, account.getId(), isOAuth);

        UpstreamHeaders headers = OpenAiAuthProfile.build(context, body);

        return HttpRequest.newBuilder()
                .uri(URI.create(targetUrl))
                .timeout(Duration.ofSeconds(120))
                .headers(headers.toArray())
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
    }

    /**
     * 规范化 OpenAI OAuth Codex 请求体。
     * <p>
     * ChatGPT 内部 Codex 端点不是公开 Responses API：它要求顶层 instructions，
     * 且不支持 max_output_tokens 等公开 Responses 字段。此逻辑只在 OAuth Codex
     * 路由层执行，避免影响普通 OpenAI API Key 的 Responses 请求。
     */
    private String normalizeCodexOAuthRequestBody(String body, AccountEntity account) {
        return normalizeCodexOAuthRequestBody(body, account, null, null, null);
    }

    private String normalizeCodexOAuthRequestBody(String body, AccountEntity account,
                                                  UpstreamRoute route, String requestId) {
        return normalizeCodexOAuthRequestBody(body, account, route, requestId, null);
    }

    private String normalizeCodexOAuthRequestBody(String body,
                                                  AccountEntity account,
                                                  UpstreamRoute route,
                                                  String requestId,
                                                  String requestedModel) {
        return codexBodyNormalizer.normalize(body, account, route, requestId, preservePromptCacheKey, requestedModel);
    }

    @Override
    public String extractUserId(String body) {
        try {
            JsonNode root = JSON.readTree(body);
            if (root.has(OpenAiResponsesBodyPolicy.FIELD_USER)) {
                JsonNode userNode = root.get(OpenAiResponsesBodyPolicy.FIELD_USER);
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
            if (root.has(GatewayRequestBodyPolicy.FIELD_MODEL)) {
                return root.get(GatewayRequestBodyPolicy.FIELD_MODEL).asText();
            }
        } catch (Exception e) {
            log.debug("Failed to extract model from OpenAI request body");
        }
        return GatewayRequestBodyPolicy.DEFAULT_MODEL;
    }

    @Override
    public boolean isStreamRequest(String body) {
        try {
            JsonNode root = JSON.readTree(body);
            if (root.has(GatewayRequestBodyPolicy.FIELD_STREAM)) {
                return root.get(GatewayRequestBodyPolicy.FIELD_STREAM).asBoolean();
            }
        } catch (Exception e) {
            // ignore
        }
        return false;
    }
}
