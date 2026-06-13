package com.landgate.trigger.gateway.handler;

import com.landgate.trigger.gateway.AbstractGatewayHandler;
import com.landgate.trigger.gateway.GatewayProtocolPlanner;
import com.landgate.trigger.gateway.error.IErrorWriter;
import com.landgate.trigger.gateway.converter.ConverterRegistry;
import com.landgate.trigger.gateway.oauth.FingerprintService;
import com.landgate.trigger.gateway.oauth.GetAccessTokenService;
import com.landgate.trigger.gateway.oauth.OAuthMimicryService;
import com.landgate.trigger.gateway.oauth.OAuthTokenRefreshService;
import com.landgate.infrastructure.upstream.HttpUpstreamClient;
import com.landgate.trigger.gateway.access.GatewayAccessService;
import com.landgate.trigger.gateway.billing.GatewayBillingSettlementService;
import com.landgate.trigger.gateway.client.ClientProfileService;
import com.landgate.trigger.gateway.error.ErrorPassthroughService;
import com.landgate.trigger.gateway.error.OpenAiErrorWriter;
import com.landgate.trigger.gateway.group.GatewayGroupResolver;
import com.landgate.trigger.gateway.limit.ConcurrencyService;
import com.landgate.trigger.gateway.limit.RateLimitHeaderParser;
import com.landgate.trigger.gateway.PlatformRouter;
import com.landgate.trigger.gateway.converter.ProtocolTranslationService;
import com.landgate.trigger.gateway.request.GatewayRequestParser;
import com.landgate.trigger.gateway.response.GatewayResponseService;
import com.landgate.trigger.gateway.route.UpstreamRouteResolver;
import com.landgate.trigger.gateway.account.AccountSelector;
import com.landgate.trigger.gateway.session.OpenAiCompatSessionService;
import com.landgate.trigger.gateway.session.SessionHashService;
import org.springframework.stereotype.Component;

/**
 * OpenAI 网关处理器 —— 使用 OpenAI 格式的错误响应。
 * <p>
 * 上游请求的构造和用量解析根据选中账户的平台动态选择，
 * 由 {@link PlatformRouter} 提供。
 */
@Component
public class OpenAiGatewayHandler extends AbstractGatewayHandler {

    private final OpenAiErrorWriter errorWriter;

    public OpenAiGatewayHandler(
            AccountSelector accountSelector,
            GetAccessTokenService getAccessTokenService,
            HttpUpstreamClient httpUpstreamClient,
            GatewayAccessService gatewayAccessService,
            ConcurrencyService concurrencyService,
            SessionHashService sessionHashService,
            OAuthTokenRefreshService oauthTokenRefreshService,
            ErrorPassthroughService errorPassthroughService,
            RateLimitHeaderParser rateLimitHeaderParser,
            PlatformRouter platformRouter,
            ProtocolTranslationService translationService,
            ConverterRegistry converterRegistry,
            GatewayProtocolPlanner protocolPlanner,
            OAuthMimicryService oAuthMimicryService,
            FingerprintService fingerprintService,
            UpstreamRouteResolver upstreamRouteResolver,
            GatewayBillingSettlementService billingSettlementService,
            GatewayGroupResolver gatewayGroupResolver,
            ClientProfileService clientProfileService,
            GatewayRequestParser gatewayRequestParser,
            GatewayResponseService gatewayResponseService,
            OpenAiCompatSessionService openAiCompatSessionService,
            OpenAiErrorWriter errorWriter) {
        super(accountSelector, getAccessTokenService, httpUpstreamClient,
                gatewayAccessService, concurrencyService, sessionHashService, oauthTokenRefreshService,
                errorPassthroughService, rateLimitHeaderParser, platformRouter,
                translationService, converterRegistry, protocolPlanner,
                oAuthMimicryService, fingerprintService,
                upstreamRouteResolver, billingSettlementService,
                gatewayGroupResolver, clientProfileService, gatewayRequestParser, gatewayResponseService,
                openAiCompatSessionService);
        this.errorWriter = errorWriter;
    }

    @Override
    protected IErrorWriter getErrorWriter() { return errorWriter; }
}
