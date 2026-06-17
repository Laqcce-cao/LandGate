package com.landgate.trigger.gateway.session;

import com.landgate.domain.account.model.entity.AccountEntity;
import com.landgate.trigger.gateway.GatewayRequestContext;
import com.landgate.trigger.gateway.response.GatewayResponseResult;
import com.landgate.types.gateway.GatewayRouteCompatibilityPolicy;
import com.landgate.types.gateway.OpenAiAccountAuthPolicy;
import com.landgate.types.gateway.OpenAiCodexProfile;
import com.landgate.types.gateway.OpenAiCompatPreviousResponsePolicy;

import java.net.http.HttpResponse;

/**
 * Applies OpenAI Anthropic-compat session state transitions after upstream I/O.
 *
 * <p>This type owns only session binding/deletion decisions for prompt-cache-key
 * compatibility. It must not write responses, perform retries, select accounts,
 * build auth headers, or mutate request bodies.</p>
 */
public final class OpenAiCompatSessionStateBinder {

    private OpenAiCompatSessionStateBinder() {
    }

    public static void bindSuccess(OpenAiCompatSessionService sessionService,
                                   GatewayRequestContext ctx,
                                   AccountEntity account,
                                   Long apiKeyId,
                                   HttpResponse<?> upstreamResp,
                                   GatewayResponseResult result) {
        if (sessionService == null || ctx == null || account == null
                || !isOpenAiAnthropicMessagesCompat(ctx)
                || trim(ctx.getOpenAiCompatPromptCacheKey()).isBlank()) {
            return;
        }
        String promptCacheKey = trim(ctx.getOpenAiCompatPromptCacheKey());

        if (OpenAiAccountAuthPolicy.isOAuthType(account.getType())
                && upstreamResp != null && upstreamResp.headers() != null) {
            String turnState = upstreamResp.headers()
                    .firstValue(OpenAiCodexProfile.HEADER_X_CODEX_TURN_STATE)
                    .orElse("")
                    .trim();
            if (!turnState.isBlank()) {
                sessionService.bindTurnState(account, apiKeyId, promptCacheKey, turnState);
            }
        }
        if (OpenAiAccountAuthPolicy.isApiKeyType(account.getType()) && result != null
                && !trim(result.responseId()).isBlank()) {
            sessionService.bindResponseId(account, apiKeyId, promptCacheKey, result.responseId());
        }
        if (!trim(ctx.getOpenAiCompatDigestChain()).isBlank()) {
            sessionService.bindAnthropicDigestPromptCacheKey(
                    account, apiKeyId, ctx.getOpenAiCompatDigestChain(), promptCacheKey,
                    ctx.getOpenAiCompatMatchedDigestChain());
        }
    }

    public static PreviousResponseFailureAction handlePreviousResponseFailure(
            OpenAiCompatSessionService sessionService,
            GatewayRequestContext ctx,
            AccountEntity account,
            Long apiKeyId,
            int statusCode,
            String errorBody) {
        if (sessionService == null || ctx == null || account == null
                || !isOpenAiAnthropicMessagesCompat(ctx)
                || trim(ctx.getOpenAiCompatPreviousResponseId()).isBlank()
                || trim(ctx.getOpenAiCompatPromptCacheKey()).isBlank()) {
            return PreviousResponseFailureAction.none();
        }
        String promptCacheKey = ctx.getOpenAiCompatPromptCacheKey();
        String previousResponseId = ctx.getOpenAiCompatPreviousResponseId();
        if (OpenAiCompatPreviousResponsePolicy.isUnsupported(statusCode, errorBody)) {
            sessionService.disableContinuation(account, apiKeyId, promptCacheKey);
            return PreviousResponseFailureAction.retryWithoutContinuation(
                    PreviousResponseFailureKind.UNSUPPORTED, previousResponseId);
        }
        if (OpenAiCompatPreviousResponsePolicy.isNotFound(statusCode, errorBody)) {
            sessionService.deleteResponseId(account, apiKeyId, promptCacheKey);
            return PreviousResponseFailureAction.retryWithoutContinuation(
                    PreviousResponseFailureKind.NOT_FOUND, previousResponseId);
        }
        return PreviousResponseFailureAction.none();
    }

    private static boolean isOpenAiAnthropicMessagesCompat(GatewayRequestContext ctx) {
        return ctx.getUpstreamRoute() != null
                && GatewayRouteCompatibilityPolicy.isOpenAiAnthropicMessagesCompat(
                ctx.getUpstreamRoute().upstreamPlatform(),
                ctx.getUpstreamRoute().clientFormat(),
                ctx.getUpstreamRoute().upstreamFormat());
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    public enum PreviousResponseFailureKind {
        NONE,
        UNSUPPORTED,
        NOT_FOUND
    }

    public record PreviousResponseFailureAction(
            PreviousResponseFailureKind kind,
            String previousResponseId
    ) {
        static PreviousResponseFailureAction none() {
            return new PreviousResponseFailureAction(PreviousResponseFailureKind.NONE, "");
        }

        static PreviousResponseFailureAction retryWithoutContinuation(
                PreviousResponseFailureKind kind,
                String previousResponseId) {
            return new PreviousResponseFailureAction(kind, trim(previousResponseId));
        }

        public boolean retryWithoutContinuation() {
            return kind != PreviousResponseFailureKind.NONE;
        }
    }
}
