package com.landgate.trigger.gateway.handler;

import com.landgate.trigger.gateway.IErrorWriter;
import com.landgate.trigger.gateway.converter.ConverterRegistry;
import com.landgate.trigger.gateway.oauth.ClaudeCodeDetector;
import com.landgate.trigger.gateway.oauth.FingerprintService;
import com.landgate.trigger.gateway.oauth.OAuthMimicryService;
import com.landgate.domain.auth.adapter.repository.IUserRepository;
import com.landgate.domain.billing.service.BillingDomainService;
import com.landgate.infrastructure.upstream.HttpUpstreamClient;
import com.landgate.trigger.gateway.*;
import com.landgate.trigger.gateway.billing.GatewayBillingSettlementService;
import com.landgate.trigger.gateway.error.OpenAiErrorWriter;
import com.landgate.trigger.gateway.group.GatewayGroupResolver;
import com.landgate.trigger.gateway.route.UpstreamRouteResolver;
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
            IUserRepository userRepository,
            BillingDomainService billingDomainService,
            BalanceDomainService balanceDomainService,
            ConcurrencyService concurrencyService,
            SessionHashService sessionHashService,
            OAuthTokenRefreshService oauthTokenRefreshService,
            ErrorPassthroughService errorPassthroughService,
            RateLimitHeaderParser rateLimitHeaderParser,
            PlatformRouter platformRouter,
            ProtocolTranslationService translationService,
            ConverterRegistry converterRegistry,
            ClaudeCodeDetector claudeCodeDetector,
            OAuthMimicryService oAuthMimicryService,
            FingerprintService fingerprintService,
            UpstreamCapabilityService upstreamCapabilityService,
            UpstreamRouteResolver upstreamRouteResolver,
            GatewayBillingSettlementService billingSettlementService,
            GatewayGroupResolver gatewayGroupResolver,
            OpenAiErrorWriter errorWriter) {
        super(accountSelector, getAccessTokenService, httpUpstreamClient,
                userRepository, billingDomainService, balanceDomainService,
                concurrencyService, sessionHashService, oauthTokenRefreshService,
                errorPassthroughService, rateLimitHeaderParser, platformRouter,
                translationService, converterRegistry,
                claudeCodeDetector, oAuthMimicryService, fingerprintService,
                upstreamCapabilityService, upstreamRouteResolver, billingSettlementService, gatewayGroupResolver);
        this.errorWriter = errorWriter;
    }

    @Override
    protected IErrorWriter getErrorWriter() { return errorWriter; }
}
