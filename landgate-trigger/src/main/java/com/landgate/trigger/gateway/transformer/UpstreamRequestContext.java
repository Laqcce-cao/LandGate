package com.landgate.trigger.gateway.transformer;

import com.landgate.domain.account.model.entity.AccountEntity;
import com.landgate.trigger.gateway.GatewayRequestContext;
import com.landgate.trigger.gateway.route.UpstreamRoute;

import java.util.Map;

/**
 * Explicit input for building an upstream HTTP request.
 * <p>
 * This keeps transformers from reaching into {@link GatewayRequestContext}'s ThreadLocal state.
 */
public record UpstreamRequestContext(
        String requestId,
        Long apiKeyId,
        String body,
        AccountEntity account,
        String accessToken,
        UpstreamRoute upstreamRoute,
        String metadataUserId,
        String upstreamPath,
        String requestedModel,
        boolean stream,
        boolean shouldMimicClaudeCode,
        Map<String, String> requestHeaders
    ) {

    public UpstreamRequestContext(
            String requestId,
            String body,
            AccountEntity account,
            String accessToken,
            UpstreamRoute upstreamRoute,
            String metadataUserId,
            String upstreamPath,
            String requestedModel,
            boolean stream,
            boolean shouldMimicClaudeCode,
            Map<String, String> requestHeaders
    ) {
        this(requestId, null, body, account, accessToken, upstreamRoute, metadataUserId, upstreamPath,
                requestedModel, stream, shouldMimicClaudeCode, requestHeaders);
    }

    public UpstreamRequestContext(
            String body,
            AccountEntity account,
            String accessToken,
            UpstreamRoute upstreamRoute,
            String metadataUserId,
            String upstreamPath,
            String requestedModel,
            boolean stream,
            boolean shouldMimicClaudeCode,
            Map<String, String> requestHeaders
    ) {
        this(null, null, body, account, accessToken, upstreamRoute, metadataUserId, upstreamPath,
                requestedModel, stream, shouldMimicClaudeCode, requestHeaders);
    }

    public static UpstreamRequestContext fromLegacy(String body, AccountEntity account, String accessToken) {
        GatewayRequestContext ctx = GatewayRequestContext.get();
        return new UpstreamRequestContext(
                ctx != null ? ctx.getRequestId() : null,
                ctx != null ? ctx.getApiKeyId() : null,
                body,
                account,
                accessToken,
                ctx != null ? ctx.getUpstreamRoute() : null,
                ctx != null ? ctx.getMetadataUserId() : null,
                ctx != null ? ctx.getUpstreamPath() : null,
                ctx != null ? ctx.getRequestedModel() : null,
                ctx != null && ctx.isStream(),
                ctx != null && ctx.isShouldMimicClaudeCode(),
                Map.of()
        );
    }
}
