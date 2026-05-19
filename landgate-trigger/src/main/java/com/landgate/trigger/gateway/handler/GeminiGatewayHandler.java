package com.landgate.trigger.gateway.handler;

import com.landgate.trigger.gateway.IErrorWriter;
import com.landgate.trigger.gateway.IRequestTransformer;
import com.landgate.trigger.gateway.IUsageParser;
import com.landgate.domain.auth.adapter.repository.IUserRepository;
import com.landgate.domain.billing.service.BillingDomainService;
import com.landgate.domain.group.adapter.repository.IGroupRepository;
import com.landgate.infrastructure.upstream.HttpUpstreamClient;
import com.landgate.trigger.gateway.*;
import com.landgate.trigger.gateway.error.GeminiErrorWriter;
import org.springframework.stereotype.Component;

@Component
public class GeminiGatewayHandler extends AbstractGatewayHandler {

    private final GeminiTransformer transformer;
    private final GeminiUsageParser usageParser;
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
            GeminiTransformer transformer,
            GeminiUsageParser usageParser,
            GeminiErrorWriter errorWriter) {
        super(accountSelector, getAccessTokenService, httpUpstreamClient,
                groupRepository, userRepository, billingDomainService, balanceDomainService,
                concurrencyService, sessionHashService, oauthTokenRefreshService);
        this.transformer = transformer;
        this.usageParser = usageParser;
        this.errorWriter = errorWriter;
    }

    @Override
    protected IRequestTransformer getTransformer() { return transformer; }

    @Override
    protected IUsageParser getUsageParser() { return usageParser; }

    @Override
    protected IErrorWriter getErrorWriter() { return errorWriter; }

    @Override
    protected String getPlatformName() { return "GEMINI"; }
}
