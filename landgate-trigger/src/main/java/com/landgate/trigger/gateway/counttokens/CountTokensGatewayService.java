package com.landgate.trigger.gateway.counttokens;

import com.landgate.domain.account.model.entity.AccountEntity;
import com.landgate.domain.group.model.entity.GroupEntity;
import com.landgate.infrastructure.upstream.HttpUpstreamClient;
import com.landgate.trigger.gateway.account.AccountSelector;
import com.landgate.trigger.gateway.access.GatewayAccessResult;
import com.landgate.trigger.gateway.access.GatewayAccessService;
import com.landgate.trigger.gateway.converter.ProtocolFormatResolver;
import com.landgate.trigger.gateway.error.AnthropicErrorWriter;
import com.landgate.trigger.gateway.forwarding.AnthropicForwardingRuntimePolicyProvider;
import com.landgate.trigger.gateway.oauth.ClaudeCodeDetector;
import com.landgate.trigger.gateway.oauth.GetAccessTokenService;
import com.landgate.trigger.gateway.request.AnthropicMessagesHttpRequestValidator;
import com.landgate.trigger.gateway.request.GatewayRequestParser;
import com.landgate.trigger.gateway.transformer.AnthropicCountTokensRequestFactory;
import com.landgate.types.enums.AccountType;
import com.landgate.types.enums.Platform;
import com.landgate.types.gateway.AnthropicApiProfile;
import com.landgate.types.gateway.AnthropicCountTokensPolicy;
import com.landgate.types.gateway.AnthropicForwardingRuntimePolicy;
import com.landgate.types.gateway.GatewayHttpHeaderPolicy;
import com.landgate.types.gateway.GatewayProtocolFormat;
import com.landgate.types.gateway.GatewayUnsupportedFeaturePolicy;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.net.http.HttpHeaders;
import java.util.Map;
import java.util.UUID;

/**
 * Handles Anthropic Messages count_tokens compatibility.
 *
 * <p>Sub2API treats count_tokens as an informational probe: it verifies inbound
 * auth and billing eligibility, selects an account, forwards only to Anthropic
 * capable upstreams, and does not consume concurrency slots or settle usage.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CountTokensGatewayService {

    private final GatewayAccessService gatewayAccessService;
    private final AccountSelector accountSelector;
    private final GetAccessTokenService getAccessTokenService;
    private final HttpUpstreamClient httpUpstreamClient;
    private final AnthropicCountTokensRequestFactory requestFactory;
    private final AnthropicCountTokensOAuthNormalizer oAuthNormalizer;
    private final AnthropicCountTokensThinkingRetryPolicy thinkingRetryPolicy;
    private final AnthropicForwardingRuntimePolicyProvider runtimePolicyProvider;
    private final AnthropicMessagesHttpRequestValidator requestValidator;
    private final AnthropicErrorWriter errorWriter;
    private final ClaudeCodeDetector claudeCodeDetector;

    public void handle(String body,
                       HttpServletRequest request,
                       HttpServletResponse response) throws IOException {
        String requestId = (String) request.getAttribute("gateway_request_id");
        if (requestId == null) {
            requestId = UUID.randomUUID().toString();
            request.setAttribute("gateway_request_id", requestId);
        }

        GatewayAccessResult access = gatewayAccessService.check(requestId, request, response, errorWriter);
        if (access.shouldStop()) {
            return;
        }

        AnthropicMessagesHttpRequestValidator.ValidationResult validation =
                requestValidator.validate(body, GatewayProtocolFormat.MESSAGES.id());
        if (!validation.accepted()) {
            errorWriter.writeError(response, validation.status(), validation.code(), validation.message());
            return;
        }

        GroupEntity group = access.group();
        if (!ProtocolFormatResolver.groupAllowsClientFormat(group, GatewayProtocolFormat.MESSAGES.id())) {
            errorWriter.writeError(response, 403, "permission_error",
                    "This group does not allow protocol: " + GatewayProtocolFormat.MESSAGES.id());
            return;
        }

        String model = GatewayRequestParser.extractModel(body);
        AccountEntity account = accountSelector.selectAccount(group, model);
        if (account == null) {
            log.warn("[{}] count_tokens account selection failed: group={}, model={}",
                    requestId, group.getName(), model);
            errorWriter.writeError(response,
                    AnthropicCountTokensPolicy.STATUS_SERVICE_UNAVAILABLE,
                    GatewayUnsupportedFeaturePolicy.ERROR_TYPE_API,
                    AnthropicCountTokensPolicy.MESSAGE_NO_AVAILABLE_ACCOUNT);
            return;
        }

        if (account.getPlatform() != Platform.ANTHROPIC) {
            log.debug("[{}] count_tokens unsupported selected platform: account_id={}, platform={}",
                    requestId, account.getId(), account.getPlatform());
            errorWriter.writeError(response,
                    AnthropicCountTokensPolicy.STATUS_NOT_FOUND,
                    GatewayUnsupportedFeaturePolicy.ERROR_TYPE_NOT_FOUND,
                    GatewayUnsupportedFeaturePolicy.COUNT_TOKENS_UNSUPPORTED_PLATFORM_MESSAGE);
            return;
        }

        String accessToken = getAccessTokenService.getAccessToken(account);
        if (accessToken == null || accessToken.isBlank()) {
            errorWriter.writeError(response,
                    AnthropicCountTokensPolicy.STATUS_BAD_GATEWAY,
                    GatewayUnsupportedFeaturePolicy.ERROR_TYPE_UPSTREAM,
                    AnthropicCountTokensPolicy.MESSAGE_GET_ACCESS_TOKEN_FAILED);
            return;
        }

        Map<String, String> requestHeaders = clientHeaders(request);
        boolean mimicClaudeCode = isAnthropicOAuth(account)
                && !claudeCodeDetector.validateForNonMessages(
                request.getHeader(GatewayHttpHeaderPolicy.HEADER_USER_AGENT));
        AnthropicForwardingRuntimePolicy runtimePolicy = runtimePolicyProvider.current();
        AnthropicCountTokensOAuthNormalizer.Result normalizedRequest =
                oAuthNormalizer.normalize(account, body, model, mimicClaudeCode, requestHeaders, runtimePolicy);

        AnthropicCountTokensRequestFactory.Options requestOptions =
                new AnthropicCountTokensRequestFactory.Options(
                        model,
                        normalizedRequest.mimicClaudeCode(),
                        normalizedRequest.fingerprint(),
                        runtimePolicy.betaDropTokens());
        HttpRequest upstreamRequest;
        try {
            upstreamRequest = requestFactory.build(
                    account,
                    accessToken,
                    normalizedRequest.body(),
                    requestHeaders,
                    requestOptions);
        } catch (Exception e) {
            log.warn("[{}] Failed to build count_tokens upstream request: account_id={}",
                    requestId, account.getId(), e);
            errorWriter.writeError(response,
                    AnthropicCountTokensPolicy.STATUS_INTERNAL_SERVER_ERROR,
                    GatewayUnsupportedFeaturePolicy.ERROR_TYPE_API,
                    AnthropicCountTokensPolicy.MESSAGE_BUILD_REQUEST_FAILED);
            return;
        }

        CountTokensUpstreamResult upstreamResult;
        try {
            upstreamResult = sendAndRead(upstreamRequest);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            errorWriter.writeError(response,
                    AnthropicCountTokensPolicy.STATUS_BAD_GATEWAY,
                    GatewayUnsupportedFeaturePolicy.ERROR_TYPE_UPSTREAM,
                    AnthropicCountTokensPolicy.MESSAGE_REQUEST_FAILED);
            return;
        } catch (IOException e) {
            log.warn("[{}] count_tokens upstream IO failed: account_id={}", requestId, account.getId(), e);
            errorWriter.writeError(response,
                    AnthropicCountTokensPolicy.STATUS_BAD_GATEWAY,
                    GatewayUnsupportedFeaturePolicy.ERROR_TYPE_UPSTREAM,
                    AnthropicCountTokensPolicy.MESSAGE_REQUEST_FAILED);
            return;
        }

        if (thinkingRetryPolicy.shouldRetry(upstreamResult.statusCode(), upstreamResult.body())) {
            log.debug("[{}] count_tokens thinking/signature error detected, retrying with filtered body: account_id={}",
                    requestId, account.getId());
            String retryBody = thinkingRetryPolicy.filterBodyForRetry(normalizedRequest.body());
            try {
                HttpRequest retryRequest = requestFactory.build(
                        account, accessToken, retryBody, requestHeaders, requestOptions);
                upstreamResult = sendAndRead(retryRequest);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                errorWriter.writeError(response,
                        AnthropicCountTokensPolicy.STATUS_BAD_GATEWAY,
                        GatewayUnsupportedFeaturePolicy.ERROR_TYPE_UPSTREAM,
                        AnthropicCountTokensPolicy.MESSAGE_REQUEST_FAILED);
                return;
            } catch (IOException e) {
                log.warn("[{}] count_tokens thinking retry IO failed: account_id={}", requestId, account.getId(), e);
                errorWriter.writeError(response,
                        AnthropicCountTokensPolicy.STATUS_BAD_GATEWAY,
                        GatewayUnsupportedFeaturePolicy.ERROR_TYPE_UPSTREAM,
                        AnthropicCountTokensPolicy.MESSAGE_REQUEST_FAILED);
                return;
            } catch (Exception e) {
                log.warn("[{}] count_tokens thinking retry build failed: account_id={}", requestId, account.getId(), e);
                errorWriter.writeError(response,
                        AnthropicCountTokensPolicy.STATUS_INTERNAL_SERVER_ERROR,
                        GatewayUnsupportedFeaturePolicy.ERROR_TYPE_API,
                        AnthropicCountTokensPolicy.MESSAGE_BUILD_REQUEST_FAILED);
                return;
            }
        }

        writeUpstreamResponse(upstreamResult, response);
    }

    private CountTokensUpstreamResult sendAndRead(HttpRequest upstreamRequest)
            throws IOException, InterruptedException {
        HttpResponse<InputStream> upstreamResponse = httpUpstreamClient.send(upstreamRequest);
        try (InputStream input = upstreamResponse.body()) {
            return new CountTokensUpstreamResult(
                    upstreamResponse.statusCode(),
                    new String(input.readAllBytes(), StandardCharsets.UTF_8),
                    upstreamResponse.headers());
        }
    }

    private void writeUpstreamResponse(CountTokensUpstreamResult upstreamResponse,
                                       HttpServletResponse response) throws IOException {
        int statusCode = upstreamResponse.statusCode();
        String body = upstreamResponse.body();
        if (AnthropicCountTokensPolicy.isUnsupportedUpstream404(statusCode, body)) {
            errorWriter.writeError(response,
                    AnthropicCountTokensPolicy.STATUS_NOT_FOUND,
                    GatewayUnsupportedFeaturePolicy.ERROR_TYPE_NOT_FOUND,
                    GatewayUnsupportedFeaturePolicy.COUNT_TOKENS_UNSUPPORTED_UPSTREAM_MESSAGE);
            return;
        }

        if (statusCode >= 400) {
            errorWriter.writeError(response,
                    statusCode,
                    GatewayUnsupportedFeaturePolicy.ERROR_TYPE_UPSTREAM,
                    AnthropicCountTokensPolicy.upstreamErrorMessage(statusCode));
            return;
        }

        response.setStatus(statusCode);
        response.setContentType(upstreamResponse.headers()
                .firstValue(GatewayHttpHeaderPolicy.HEADER_CONTENT_TYPE)
                .orElse(AnthropicApiProfile.MEDIA_TYPE_JSON));
        response.getWriter().write(body);
    }

    private static Map<String, String> clientHeaders(HttpServletRequest request) {
        if (request == null) {
            return Map.of();
        }
        Enumeration<String> names = request.getHeaderNames();
        if (names == null) {
            return Map.of();
        }
        Map<String, String> headers = new LinkedHashMap<>();
        for (String name : Collections.list(names)) {
            headers.put(name, request.getHeader(name));
        }
        return headers;
    }

    private static boolean isAnthropicOAuth(AccountEntity account) {
        return account != null
                && (account.getType() == AccountType.OAUTH || account.getType() == AccountType.SETUP_TOKEN);
    }

    private record CountTokensUpstreamResult(int statusCode, String body, HttpHeaders headers) {
    }
}
