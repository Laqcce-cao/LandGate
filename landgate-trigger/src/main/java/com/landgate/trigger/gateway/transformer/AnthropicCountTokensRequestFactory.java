package com.landgate.trigger.gateway.transformer;

import com.landgate.domain.account.model.entity.AccountEntity;
import com.landgate.trigger.gateway.oauth.FingerprintService;
import com.landgate.trigger.gateway.oauth.OAuthMimicryService;
import com.landgate.trigger.gateway.route.UpstreamEndpointDefaults;
import com.landgate.types.gateway.AnthropicAccountAuthPolicy;
import com.landgate.types.gateway.AnthropicApiProfile;
import com.landgate.types.gateway.AnthropicClaudeCodeProfile;
import com.landgate.types.gateway.GatewayHeaderPolicy;
import com.landgate.types.gateway.MetadataUserIdParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Builds Anthropic Messages count_tokens upstream requests.
 *
 * <p>Routing owns the endpoint URL; {@link AnthropicAuthProfile} owns
 * credential/header semantics. This factory only combines those typed policies
 * into a concrete JDK request.</p>
 */
@Slf4j
@Component
public class AnthropicCountTokensRequestFactory {

    private final OAuthMimicryService oAuthMimicryService;
    private final FingerprintService fingerprintService;

    public AnthropicCountTokensRequestFactory(OAuthMimicryService oAuthMimicryService,
                                              FingerprintService fingerprintService) {
        this.oAuthMimicryService = oAuthMimicryService;
        this.fingerprintService = fingerprintService;
    }

    public HttpRequest build(AccountEntity account,
                             String accessToken,
                             String body,
                             Map<String, String> requestHeaders) {
        return build(account, accessToken, body, requestHeaders, Options.defaults());
    }

    public HttpRequest build(AccountEntity account,
                             String accessToken,
                             String body,
                             Map<String, String> requestHeaders,
                             Options options) {
        Options effective = options == null ? Options.defaults() : options;
        String upstreamBody = AnthropicModelMappingBodyNormalizer.apply(account, body);
        String targetUrl = UpstreamEndpointDefaults.anthropicMessagesCountTokensUrl(account);
        Map<String, String> upstreamHeaders = normalizeApiKeyCountTokensHeaders(account, requestHeaders, effective);
        String[] headers = AnthropicAuthProfile.from(account).buildHeaders(accessToken, upstreamHeaders);
        log.debug("Building Anthropic count_tokens upstream request: url={}, account_id={}",
                targetUrl, account != null ? account.getId() : null);

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(targetUrl))
                .timeout(Duration.ofSeconds(120))
                .headers(headers)
                .setHeader(AnthropicApiProfile.HEADER_CONTENT_TYPE, AnthropicApiProfile.MEDIA_TYPE_JSON)
                .POST(HttpRequest.BodyPublishers.ofString(upstreamBody, StandardCharsets.UTF_8));

        applyOAuthCountTokensHeaders(builder, account, upstreamHeaders, effective);
        syncClaudeCodeSessionHeader(builder, upstreamBody, requestHeaders);
        return builder.build();
    }

    private void applyOAuthCountTokensHeaders(HttpRequest.Builder builder,
                                              AccountEntity account,
                                              Map<String, String> requestHeaders,
                                              Options options) {
        if (!isAnthropicOAuth(account)) {
            return;
        }
        if (options.mimicClaudeCode()) {
            oAuthMimicryService.applyClaudeCodeMimicHeaders(builder, false, options.model(), requestHeaders);
            builder.setHeader(AnthropicApiProfile.HEADER_ANTHROPIC_BETA,
                    AnthropicClaudeCodeProfile.mergeBetaHeader(
                            AnthropicClaudeCodeProfile.fullMimicryCountTokensBetas(),
                            GatewayHeaderPolicy.value(requestHeaders, AnthropicApiProfile.HEADER_ANTHROPIC_BETA),
                            options.droppedBetas()));
            builder.setHeader(AnthropicApiProfile.HEADER_X_CLIENT_REQUEST_ID, UUID.randomUUID().toString());
            return;
        }

        oAuthMimicryService.applyOAuthHeaderDefaults(builder, options.model(), requestHeaders);
        if (options.fingerprint() != null) {
            fingerprintService.applyFingerprint(builder, options.fingerprint());
        }
        builder.setHeader(AnthropicApiProfile.HEADER_ANTHROPIC_BETA,
                AnthropicClaudeCodeProfile.ensureCountTokensOAuthBetaHeader(
                        options.model(),
                        GatewayHeaderPolicy.value(requestHeaders, AnthropicApiProfile.HEADER_ANTHROPIC_BETA),
                        options.droppedBetas()));
    }

    private static Map<String, String> normalizeApiKeyCountTokensHeaders(AccountEntity account,
                                                                         Map<String, String> requestHeaders,
                                                                         Options options) {
        if (isAnthropicOAuth(account) || requestHeaders == null || requestHeaders.isEmpty()) {
            return requestHeaders == null ? Map.of() : requestHeaders;
        }
        Set<String> droppedBetas = options.droppedBetas();
        if (droppedBetas.isEmpty()
                || !GatewayHeaderPolicy.hasValue(requestHeaders, AnthropicApiProfile.HEADER_ANTHROPIC_BETA)) {
            return requestHeaders;
        }

        Map<String, String> normalized = new LinkedHashMap<>(requestHeaders);
        String stripped = AnthropicClaudeCodeProfile.stripBetaTokens(
                GatewayHeaderPolicy.value(requestHeaders, AnthropicApiProfile.HEADER_ANTHROPIC_BETA),
                droppedBetas);
        normalized.entrySet().removeIf(entry ->
                entry.getKey() != null
                        && entry.getKey().equalsIgnoreCase(AnthropicApiProfile.HEADER_ANTHROPIC_BETA));
        if (!stripped.isBlank()) {
            normalized.put(AnthropicApiProfile.HEADER_ANTHROPIC_BETA, stripped);
        }
        return normalized;
    }

    private static void syncClaudeCodeSessionHeader(HttpRequest.Builder builder,
                                                    String body,
                                                    Map<String, String> requestHeaders) {
        if (builder == null
                || body == null
                || !GatewayHeaderPolicy.hasValue(requestHeaders, AnthropicApiProfile.HEADER_X_CLAUDE_CODE_SESSION_ID)) {
            return;
        }
        String userId = MetadataUserIdParser.extractFromBody(body);
        MetadataUserIdParser.ParsedMetadataUserId parsed = MetadataUserIdParser.parse(userId);
        if (parsed != null && parsed.sessionId() != null && !parsed.sessionId().isBlank()) {
            builder.setHeader(AnthropicApiProfile.HEADER_X_CLAUDE_CODE_SESSION_ID, parsed.sessionId());
        }
    }

    private static boolean isAnthropicOAuth(AccountEntity account) {
        return account != null
                && AnthropicAccountAuthPolicy.isOAuthOrSetupTokenType(account.getType());
    }

    public record Options(
            String model,
            boolean mimicClaudeCode,
            FingerprintService.ClientFingerprint fingerprint,
            Set<String> droppedBetas
    ) {
        public Options {
            droppedBetas = droppedBetas == null ? Set.of() : Set.copyOf(droppedBetas);
        }

        public static Options defaults() {
            return new Options("", false, null, Set.of());
        }
    }
}
