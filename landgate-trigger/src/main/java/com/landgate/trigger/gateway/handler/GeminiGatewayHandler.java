package com.landgate.trigger.gateway.handler;

import com.landgate.trigger.gateway.IErrorWriter;
import com.landgate.domain.auth.adapter.repository.IUserRepository;
import com.landgate.domain.billing.service.BillingDomainService;
import com.landgate.domain.group.adapter.repository.IGroupRepository;
import com.landgate.infrastructure.upstream.HttpUpstreamClient;
import com.landgate.trigger.gateway.*;
import com.landgate.trigger.gateway.error.GeminiErrorWriter;
import org.springframework.stereotype.Component;

/**
 * Gemini 网关处理器 —— 使用 Gemini 格式的错误响应。
 * <p>
 * 上游请求的构造和用量解析根据选中账户的平台动态选择，
 * 由 {@link PlatformRouter} 提供。
 */
@Component
public class GeminiGatewayHandler extends AbstractGatewayHandler {

    private final GeminiErrorWriter errorWriter;

    public GeminiGatewayHandler(
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
            GeminiErrorWriter errorWriter) {
        super(accountSelector, getAccessTokenService, httpUpstreamClient,
                groupRepository, userRepository, billingDomainService, balanceDomainService,
                concurrencyService, sessionHashService, oauthTokenRefreshService,
                errorPassthroughService, rateLimitHeaderParser, platformRouter);
        this.errorWriter = errorWriter;
    }

    @Override
    protected IErrorWriter getErrorWriter() { return errorWriter; }
}
