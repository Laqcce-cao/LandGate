package com.landgate.trigger.gateway.handler;

import com.landgate.trigger.gateway.IErrorWriter;
import com.landgate.trigger.gateway.converter.ConverterRegistry;
import com.landgate.trigger.gateway.oauth.ClaudeCodeDetector;
import com.landgate.trigger.gateway.oauth.FingerprintService;
import com.landgate.trigger.gateway.oauth.OAuthMimicryService;
import com.landgate.domain.auth.adapter.repository.IUserRepository;
import com.landgate.domain.billing.service.BillingDomainService;
import com.landgate.domain.group.adapter.repository.IGroupRepository;
import com.landgate.infrastructure.upstream.HttpUpstreamClient;
import com.landgate.trigger.gateway.*;
import com.landgate.trigger.gateway.billing.GatewayBillingSettlementService;
import com.landgate.trigger.gateway.error.AnthropicErrorWriter;
import com.landgate.trigger.gateway.route.UpstreamRouteResolver;
import org.springframework.stereotype.Component;

/**
 * Antigravity 网关处理器 —— 处理 Antigravity 平台的请求转发。
 * <p>
 * Antigravity 协议与 Anthropic Messages API 完全兼容，故错误响应格式复用
 * {@link AnthropicErrorWriter}。上游请求构造由 {@link PlatformRouter} 根据账号平台
 * 路由到 {@link AntigravityTransformer}（继承自 Anthropic Transformer 但绑定到 Antigravity 平台）。
 * <p>
 * 与 {@link AnthropicGatewayHandler} 单独存在的意义：在 Handler 层隔离 Antigravity 与 Anthropic
 * 的处理路径，确保后续 Phase 引入的 OAuth 伪装等 Anthropic 专属逻辑不会误用于 Antigravity 账号
 * （参照 Sub2API 的 AntigravityGatewayService 独立分支）。
 */
@Component
public class AntigravityGatewayHandler extends AbstractGatewayHandler {

    private final AnthropicErrorWriter errorWriter;

    public AntigravityGatewayHandler(
            AccountSelector accountSelector,
            GetAccessTokenService getAccessTokenService,
            HttpUpstreamClient httpUpstreamClient,
            IGroupRepository groupRepository,
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
            AnthropicErrorWriter errorWriter) {
        super(accountSelector, getAccessTokenService, httpUpstreamClient,
                groupRepository, userRepository, billingDomainService, balanceDomainService,
                concurrencyService, sessionHashService, oauthTokenRefreshService,
                errorPassthroughService, rateLimitHeaderParser, platformRouter,
                translationService, converterRegistry,
                claudeCodeDetector, oAuthMimicryService, fingerprintService,
                upstreamCapabilityService, upstreamRouteResolver, billingSettlementService);
        this.errorWriter = errorWriter;
    }

    @Override
    protected IErrorWriter getErrorWriter() { return errorWriter; }
}
