package com.landgate.trigger.gateway;

import com.landgate.domain.account.model.entity.AccountEntity;
import com.landgate.domain.auth.model.entity.UserEntity;
import com.landgate.domain.billing.model.valobj.UsageTokens;
import com.landgate.domain.group.model.entity.GroupEntity;
import com.landgate.infrastructure.upstream.HttpUpstreamClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.landgate.trigger.gateway.access.GatewayAccessResult;
import com.landgate.trigger.gateway.access.GatewayAccessService;
import com.landgate.trigger.gateway.converter.ConverterRegistry;
import com.landgate.trigger.gateway.oauth.ClaudeCodeOnlyException;
import com.landgate.trigger.gateway.oauth.FingerprintService;
import com.landgate.trigger.gateway.oauth.OAuthMimicryService;
import com.landgate.trigger.gateway.billing.GatewayBillingSettlementService;
import com.landgate.trigger.gateway.client.ClientProfile;
import com.landgate.trigger.gateway.client.ClientProfileService;
import com.landgate.trigger.gateway.group.GatewayGroupResolver;
import com.landgate.trigger.gateway.request.GatewayRequestInfo;
import com.landgate.trigger.gateway.request.GatewayRequestParser;
import com.landgate.trigger.gateway.response.GatewayResponseResult;
import com.landgate.trigger.gateway.response.GatewayResponseService;
import com.landgate.trigger.gateway.route.UpstreamRoute;
import com.landgate.trigger.gateway.route.UpstreamRouteRequest;
import com.landgate.trigger.gateway.route.UpstreamRouteResolver;
import com.landgate.types.enums.AccountType;
import com.landgate.types.enums.Platform;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 网关处理抽象基类 —— 模板方法定义通用的 AI API 代理处理流程。
 * <p>
 * 子类只需注入平台特定的三个策略：
 * <ul>
 *   <li>{@link IRequestTransformer} — 请求构建</li>
 *   <li>{@link IUsageParser} — 用量解析</li>
 *   <li>{@link IErrorWriter} — 错误响应</li>
 * </ul>
 * <p>
 * 模板方法封装了完整的 failover 循环（最多 3 次），
 * 包括认证校验、余额预检查、会话粘滞、并发控制、OAuth 刷新和计费扣减。
 */
@Slf4j
public abstract class AbstractGatewayHandler implements IGatewayHandler {

    protected final AccountSelector accountSelector;
    protected final GetAccessTokenService getAccessTokenService;
    protected final HttpUpstreamClient httpUpstreamClient;
    protected final GatewayAccessService gatewayAccessService;
    protected final ConcurrencyService concurrencyService;
    protected final SessionHashService sessionHashService;
    protected final OAuthTokenRefreshService oauthTokenRefreshService;
    protected final ErrorPassthroughService errorPassthroughService;
    protected final RateLimitHeaderParser rateLimitHeaderParser;
    protected final PlatformRouter platformRouter;

    protected final ProtocolTranslationService translationService;
    protected final ConverterRegistry converterRegistry;

    protected final OAuthMimicryService oAuthMimicryService;
    protected final FingerprintService fingerprintService;
    protected final UpstreamCapabilityService upstreamCapabilityService;
    protected final UpstreamRouteResolver upstreamRouteResolver;
    protected final GatewayBillingSettlementService billingSettlementService;
    protected final GatewayGroupResolver gatewayGroupResolver;
    protected final ClientProfileService clientProfileService;
    protected final GatewayRequestParser gatewayRequestParser;
    protected final GatewayResponseService gatewayResponseService;

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
    private static final int MAX_FAILOVER_SWITCHES = 3;

    protected AbstractGatewayHandler(
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
            OAuthMimicryService oAuthMimicryService,
            FingerprintService fingerprintService,
            UpstreamCapabilityService upstreamCapabilityService,
            UpstreamRouteResolver upstreamRouteResolver,
            GatewayBillingSettlementService billingSettlementService,
            GatewayGroupResolver gatewayGroupResolver,
            ClientProfileService clientProfileService,
            GatewayRequestParser gatewayRequestParser,
            GatewayResponseService gatewayResponseService) {
        this.accountSelector = accountSelector;
        this.getAccessTokenService = getAccessTokenService;
        this.httpUpstreamClient = httpUpstreamClient;
        this.gatewayAccessService = gatewayAccessService;
        this.concurrencyService = concurrencyService;
        this.sessionHashService = sessionHashService;
        this.oauthTokenRefreshService = oauthTokenRefreshService;
        this.errorPassthroughService = errorPassthroughService;
        this.rateLimitHeaderParser = rateLimitHeaderParser;
        this.platformRouter = platformRouter;
        this.translationService = translationService;
        this.converterRegistry = converterRegistry;
        this.oAuthMimicryService = oAuthMimicryService;
        this.fingerprintService = fingerprintService;
        this.upstreamCapabilityService = upstreamCapabilityService;
        this.upstreamRouteResolver = upstreamRouteResolver;
        this.billingSettlementService = billingSettlementService;
        this.gatewayGroupResolver = gatewayGroupResolver;
        this.clientProfileService = clientProfileService;
        this.gatewayRequestParser = gatewayRequestParser;
        this.gatewayResponseService = gatewayResponseService != null
                ? gatewayResponseService
                : new GatewayResponseService(concurrencyService, translationService, converterRegistry);
    }

    // --- 子类需注入的策略钩子 ---

    /** 客户端错误响应写入器（按客户端请求的格式返回错误） */
    protected abstract IErrorWriter getErrorWriter();

    // --- 平台感知的动态组件获取 ---

    /** 根据账户平台获取对应的上游请求转换器 */
    protected IRequestTransformer getTransformerFor(AccountEntity account) {
        return platformRouter.getTransformer(account.getPlatform());
    }

    /** 根据账户平台获取对应的用量解析器 */
    protected IUsageParser getUsageParserFor(AccountEntity account) {
        // 优先使用 ctx.requestFormat（URL 路径决定）进行细化路由，
        // 用于区分 OpenAI 平台 chat/responses 两种端点的 usage schema 差异。
        GatewayRequestContext ctx = GatewayRequestContext.get();
        if (ctx != null && ctx.getUpstreamRoute() != null) {
            return platformRouter.getUsageParser(ctx.getUpstreamRoute());
        }
        String format = ctx != null ? ctx.getRequestFormat() : null;
        return platformRouter.getUsageParser(account.getPlatform(), format);
    }

    /** 根据客户端/路由意图和上游实际 Content-Type 决定响应处理方式。 */
    protected static boolean shouldHandleResponseAsStreaming(boolean stream,
                                                            HttpResponse<InputStream> upstreamResp) {
        if (stream) return true;
        if (upstreamResp == null || upstreamResp.headers() == null) return false;
        return upstreamResp.headers().firstValue("content-type")
                .map(contentType -> contentType.toLowerCase().contains("text/event-stream"))
                .orElse(false);
    }

    // ========================
    // 模板方法
    // ========================

    @Override
    public void handle(String body, HttpServletRequest request,
                       HttpServletResponse response) throws IOException {
        long startTime = System.currentTimeMillis();

        // Step 1: 提取请求上下文
        String requestId = (String) request.getAttribute("gateway_request_id");
        if (requestId == null) {
            requestId = UUID.randomUUID().toString();
        }

        GatewayAccessResult access = gatewayAccessService.check(requestId, request, response, getErrorWriter());
        if (access.shouldStop()) {
            return;
        }
        Long apiKeyId = access.apiKeyId();
        Long userId = access.userId();
        GroupEntity group = access.group();
        UserEntity user = access.user();

        // Step 2.6: 客户端识别 + claude_code_only 分组降级
        ClientProfile clientProfile = clientProfileService.detect(body, request);
        Platform requestPlatform = clientProfile.requestPlatform();
        String requestFormat = clientProfile.requestFormat();
        boolean isClaudeCode = clientProfile.claudeCode();
        String metadataUserId = clientProfile.metadataUserId();
        log.info("[{}] 请求上下文: platform={}, format={}, group={}, api_key_id={}, user_id={}",
                requestId, requestPlatform, requestFormat, group.getName(), apiKeyId, userId);
        if (requestPlatform == Platform.ANTHROPIC) {
            log.info("[{}] Claude Code 检测: is_claude_code={}, metadata_user_id={}, format={}",
                    requestId, isClaudeCode, metadataUserId, requestFormat);
        }

        // 非 /v1/messages 端点的 claude_code_only 分组直接拒绝
        if (Boolean.TRUE.equals(group.getClaudeCodeOnly()) && requestPlatform != Platform.ANTHROPIC) {
            log.warn("[{}] 拒绝非 Anthropic 端点的 claude_code_only 分组: group={}, platform={}",
                    requestId, group.getName(), requestPlatform);
            getErrorWriter().writeError(response, 403, "permission_error",
                    "This group is restricted to Claude Code clients (/v1/messages only)");
            return;
        }

        // /v1/messages 端点走降级链路
        try {
            GroupEntity resolvedGroup = gatewayGroupResolver.resolveGatewayGroup(group, isClaudeCode);
            if (resolvedGroup != group) {
                log.info("[{}] Claude Code 分组降级: {} -> {} (is_claude_code={})",
                        requestId, group.getName(), resolvedGroup.getName(), isClaudeCode);
            }
            group = resolvedGroup;
        } catch (ClaudeCodeOnlyException e) {
            log.warn("[{}] Claude Code 分组降级失败: group={}, reason={}",
                    requestId, group.getName(), e.getMessage());
            getErrorWriter().writeError(response, 403, "permission_error", e.getMessage());
            return;
        }

        // Step 3: 流式检测 + 模型名提取（通用解析，与平台无关）
        GatewayRequestInfo requestInfo = gatewayRequestParser.parse(body, request, requestFormat);
        String model = requestInfo.model();
        String upstreamPath = requestInfo.upstreamPath();
        boolean clientStream = requestInfo.clientStream();

        log.info("[{}] 请求解析: model={}, stream={}, request_format={}",
                requestId, model, clientStream, requestFormat);

        // Step 4: Session 粘滞（IP + UA + API Key）
        String sessionHash = sessionHashService.generateHash(request, apiKeyId);
        Long stickyAccountId = sessionHashService.getBoundAccount(sessionHash);
        if (stickyAccountId != null) {
            log.info("[{}] 粘滞会话命中: account_id={}", requestId, stickyAccountId);
        }

        // Step 6: Failover 循环
        int failoverCount = 0;
        AccountEntity account = null;
        Long tokenRefreshed = null;  // 记录本轮已刷新过的 account，避免死循环

        while (failoverCount < MAX_FAILOVER_SWITCHES) {
            log.info("[{}] Failover 尝试 #{}/{}: sticky_account={}",
                    requestId, failoverCount + 1, MAX_FAILOVER_SWITCHES, stickyAccountId);

            // 粘滞账户优先，但须验证是否支持当前模型；不支持则清除粘滞走正常选择
            if (stickyAccountId != null) {
                account = accountSelector.getById(stickyAccountId);
                if (account != null && model != null
                        && !accountSelector.isModelSupportedByAccount(account, model)) {
                    log.info("[{}] 粘滞账户不支持模型，清除粘滞: account_id={}, model={}",
                            requestId, account.getId(), model);
                    sessionHashService.clearSession(sessionHash);
                    account = null;
                }
            } else {
                account = null;
            }

            if (account == null) {
                account = accountSelector.selectAccount(group, model);
                if (account != null) {
                    log.info("[{}] 账户选择结果: account_id={}, name={}, platform={}",
                            requestId, account.getId(), account.getName(), account.getPlatform());
                }
            }
            stickyAccountId = null;

            if (account == null) {
                log.warn("[{}] 无可用账户: group={}, model={}, failover={}",
                        requestId, group.getName(), model, failoverCount);
                getErrorWriter().writeError(response, 503, "overloaded_error",
                        "No available accounts in group '" + group.getName() + "'.");
                return;
            }

            ConcurrencySlot slot = concurrencyService.tryAcquire(account.getId(), account.getConcurrency());
            if (slot == null) {
                log.warn("[{}] 并发槽位不可用: account_id={}, max_concurrency={}, failover={}",
                        requestId, account.getId(), account.getConcurrency(), failoverCount);
                failoverCount++;
                continue;
            }
            log.info("[{}] 并发槽位获取成功: account_id={}", requestId, account.getId());

            String accessToken = getAccessTokenService.getAccessToken(account);
            if (accessToken == null || accessToken.isEmpty()) {
                log.warn("[{}] 获取 AccessToken 失败: account_id={}, type={}, failover={}",
                        requestId, account.getId(), account.getType(), failoverCount);
                concurrencyService.release(slot);
                failoverCount++;
                continue;
            }

            // OAuth 伪装决策（必须在 ctx builder 之前计算，供 Phase B 使用）
            // Body 级 Phase A 操作仍需在协议翻译之后执行（见 try 块内）
            boolean shouldMimicClaudeCode = account.getPlatform() == Platform.ANTHROPIC
                    && (account.getType() == AccountType.OAUTH
                        || account.getType() == AccountType.SETUP_TOKEN)
                    && !isClaudeCode;

            UpstreamRoute upstreamRoute = upstreamRouteResolver.resolve(UpstreamRouteRequest.builder()
                    .account(account)
                    .requestPlatform(requestPlatform)
                    .requestFormat(requestFormat)
                    .upstreamPath(upstreamPath)
                    .requestedModel(model)
                    .build());
            log.info("[{}] 上游路由: endpoint={}, upstream_format={}, target={}, reason={}",
                    requestId, upstreamRoute.endpointKind(), upstreamRoute.upstreamFormat(),
                    upstreamRoute.targetUrl(), upstreamRoute.reason());

            boolean upstreamStream = upstreamRoute.forceStreaming() || clientStream;

            var ctx = GatewayRequestContext.builder()
                    .requestId(requestId).apiKeyId(apiKeyId).userId(userId)
                    .group(group).selectedAccount(account)
                    .stream(upstreamStream).requestedModel(model)
                    .requestPlatform(requestPlatform)
                    .requestFormat(requestFormat)
                    .upstreamPath(upstreamPath)
                    .upstreamRoute(upstreamRoute)
                    .concurrencySlot(slot)
                    .metadataUserId(metadataUserId)
                    .claudeCode(isClaudeCode)
                    .shouldMimicClaudeCode(shouldMimicClaudeCode)
                    .resolvedGroup(group)
                    .build();
            GatewayRequestContext.set(ctx);

            try {
                // 请求协议翻译：客户端格式 ≠ 上游格式时转换 body。
                // 上游格式由 UpstreamRoute 统一决策，避免在 Handler 中散落平台/账号类型特判。
                Platform accountPlatform = account.getPlatform();
                String clientFormat = upstreamRoute.clientFormat() != null
                        ? upstreamRoute.clientFormat()
                        : ProtocolTranslationService.platformToFormatId(requestPlatform);
                String upstreamFormat = upstreamRoute.upstreamFormat();
                boolean needTranslation = clientFormat != null && upstreamFormat != null
                        && !clientFormat.equals(upstreamFormat);
                String upstreamBody = body;

                if (needTranslation && !upstreamRoute.passthrough()) {
                    log.info("[{}] 协议翻译: {} -> {} | account_id={}, platform={}",
                            requestId, clientFormat, upstreamFormat, account.getId(), accountPlatform.name());
                    upstreamBody = translationService.translateRequest(body, clientFormat, upstreamFormat);
                } else {
                    log.info("[{}] 无需协议翻译: client_format={}, upstream_format={}, passthrough={}",
                            requestId, clientFormat, upstreamFormat, upstreamRoute.passthrough());
                }

                // Phase A: OAuth 伪装 — Body 级操作（仅在 failover 循环内执行）
                // 必须在协议翻译之后执行！rewriteSystemForNonClaudeCode 和
                // normalizeClaudeOAuthRequestBody 操作的是 Anthropic Messages 格式的 body。
                // 对于 Chat Completions/Responses 客户端 → Anthropic 上游的场景，
                // upstreamBody 此时已翻译为 Anthropic 格式。
                if (shouldMimicClaudeCode) {
                    log.info("[{}] OAuth 伪装: account_id={}, model={}, type={}",
                            requestId, account.getId(), model, account.getType());
                    if (model != null && !model.toLowerCase().contains("haiku")) {
                        upstreamBody = oAuthMimicryService.rewriteSystemForNonClaudeCode(
                                upstreamBody, model);
                    }
                    // 获取或创建指纹，用于 metadata.user_id 构建
                    com.landgate.trigger.gateway.oauth.FingerprintService.ClientFingerprint fp =
                            fingerprintService.getOrCreateFingerprint(
                                    account.getId(),
                                    clientProfile.headers());
                    upstreamBody = oAuthMimicryService.buildAndInjectMetadataUserID(
                            upstreamBody, account, fp);
                    upstreamBody = oAuthMimicryService.normalizeClaudeOAuthRequestBody(
                            upstreamBody, model);
                }

                // Passthrough 模式：跳过协议翻译，直接透传原始 body。
                if (upstreamRoute.passthrough()) {
                    log.info("[{}] Passthrough 透传模式: 跳过协议翻译 | account_id={}",
                            requestId, account.getId());
                    upstreamBody = body;
                }

                // 根据选中账户的平台构造对应的上游请求
                IRequestTransformer transformer = getTransformerFor(account);
                HttpRequest upstreamReq;
                try {
                    upstreamReq = transformer.buildUpstreamRequest(new UpstreamRequestContext(
                            upstreamBody,
                            account,
                            accessToken,
                            upstreamRoute,
                            metadataUserId,
                            upstreamPath,
                            model,
                            upstreamStream,
                            shouldMimicClaudeCode,
                            clientProfile.headers()));
                } catch (Exception e) {
                    log.error("Failed to build upstream request: account_id={}", account.getId(), e);
                    concurrencyService.release(slot);
                    failoverCount++;
                    continue;
                }

                log.info("[{}] 转发上游: account={}(id={}), model={}, platform={}, client_stream={}, upstream_stream={}, attempt={}/{}",
                        requestId, account.getName(), account.getId(), model,
                        accountPlatform.name(), clientStream, upstreamStream, failoverCount + 1, MAX_FAILOVER_SWITCHES);

                HttpResponse<InputStream> upstreamResp;
                try {
                    upstreamResp = httpUpstreamClient.send(upstreamReq);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    concurrencyService.release(slot);
                    getErrorWriter().writeError(response, 502, "upstream_error", "Request interrupted");
                    return;
                } catch (IOException e) {
                    log.error("Upstream IO error: account_id={}, failover={}", account.getId(), failoverCount, e);
                    concurrencyService.release(slot);
                    failoverCount++;
                    continue;
                }

                int statusCode = upstreamResp.statusCode();
                long durationMs = System.currentTimeMillis() - startTime;
                log.info("[{}] 上游响应: status={}, elapsed={}ms, account={}(id={}), platform={}, attempt={}/{}",
                        requestId, statusCode, durationMs, account.getName(), account.getId(),
                        accountPlatform.name(), failoverCount + 1, MAX_FAILOVER_SWITCHES);

                // --- 成功 (2xx) ---
                if (statusCode >= 200 && statusCode < 300) {
                    sessionHashService.bindSession(sessionHash, account.getId());
                    log.info("[{}] 粘滞会话绑定: account_id={}", requestId, account.getId());

                    IUsageParser usageParser = getUsageParserFor(account);
                    UsageTokens usage;
                    boolean handleAsStreaming = shouldHandleResponseAsStreaming(upstreamStream, upstreamResp);
                    GatewayResponseResult streamingResult = null;
                    if (handleAsStreaming && clientStream) {
                        log.info("[{}] 开始流式响应处理", requestId);
                        streamingResult = handleStreaming(upstreamResp, response, ctx, usageParser);
                        usage = streamingResult.usage();
                    } else if (handleAsStreaming) {
                        log.info("[{}] 开始上游流式聚合为非流式响应", requestId);
                        usage = handleStreamingAsNonStreaming(upstreamResp, response, ctx, usageParser);
                    } else {
                        log.info("[{}] 开始非流式响应处理", requestId);
                        usage = handleNonStreaming(upstreamResp, response, usageParser);
                    }

                    if (usage != null && usage.hasUsage()) {
                        log.info("[{}] 用量统计: input={}, output={}, cache_write={}, cache_read={}",
                                requestId, usage.getInputTokens(), usage.getOutputTokens(),
                                usage.getCacheCreationTokens(), usage.getCacheReadTokens());
                        billingSettlementService.settleUsageLog(usage, model, accountPlatform.name(), userId, apiKeyId, account, group, user,
                                clientStream, streamingResult != null && streamingResult.clientDisconnected(), durationMs,
                                request, requestId);
                    } else {
                        log.warn("[{}] 上游成功但未解析到用量，不写入 usage_logs: account_id={}, platform={}, endpoint={}, parser={}, client_stream={}, upstream_stream={}, handled_as_stream={}, content_type={}",
                                requestId, account.getId(), accountPlatform.name(), upstreamRoute.endpointKind(),
                                usageParser.getClass().getSimpleName(), clientStream, upstreamStream, handleAsStreaming,
                                upstreamResp.headers().firstValue("Content-Type").orElse(""));
                    }

                    // 捕获上游 Rate Limit 头（仅 OAUTH 账号）
                    RateLimitSnapshot rateLimitSnapshot = null;
                    if (account.getType() == AccountType.OAUTH) {
                        logOpenAiOAuthQuotaHeaders(requestId, account, upstreamResp.headers());
                        rateLimitSnapshot = rateLimitHeaderParser.parse(
                                upstreamResp.headers(), account.getPlatform());
                    }
                    accountSelector.updateLastUsedAndRateLimits(account.getId(), rateLimitSnapshot);
                    concurrencyService.release(slot);
                    log.info("[{}] 请求完成: total_elapsed={}ms, account={}, model={}",
                            requestId, durationMs, account.getName(), model);
                    return;
                }

                // --- 401 处理 ---
                else if (statusCode == 401) {
                    concurrencyService.release(slot);

                    // 非 OAUTH 账号 401：API Key 凭证级故障，无法自愈，标记 ERROR
                    if (account.getType() != AccountType.OAUTH) {
                        log.error("[{}] 非 OAuth 账户返回 401，标记 ERROR: account_id={}, type={}",
                                requestId, account.getId(), account.getType());
                        accountSelector.markError(account.getId(),
                                "Upstream returned 401 for " + account.getType() + " account");
                        failoverCount++;
                        continue;
                    }

                    // OAUTH：已刷新过但仍 401，刷新无效，凭证级故障
                    if (account.getId().equals(tokenRefreshed)) {
                        log.warn("[{}] Token 刷新后仍 401，标记 ERROR: account_id={}", requestId, account.getId());
                        accountSelector.markError(account.getId(),
                                "OAuth token refreshed but upstream still returned 401");
                        tokenRefreshed = null;
                        failoverCount++;
                        continue;
                    }

                    log.info("[{}] OAuth 401 检测，尝试刷新 Token: account_id={}", requestId, account.getId());
                    String newToken = oauthTokenRefreshService.refreshAccessToken(account.getId());
                    if (newToken != null) {
                        log.info("[{}] OAuth Token 刷新成功，重试: account_id={}", requestId, account.getId());
                        tokenRefreshed = account.getId();
                        stickyAccountId = account.getId();
                        continue;
                    }

                    // 刷新返回 null：区分"无 refresh_token"（永久）和"刷新失败"（临时）
                    boolean hasRefreshToken = checkHasRefreshToken(account);
                    if (!hasRefreshToken) {
                        log.error("[{}] OAuth 账户无 refresh_token，标记 ERROR: account_id={}",
                                requestId, account.getId());
                        accountSelector.markError(account.getId(),
                                "OAuth account has no refresh_token, cannot recover from 401");
                    } else {
                        log.warn("[{}] OAuth Token 刷新临时失败，标记不健康: account_id={}",
                                requestId, account.getId());
                        accountSelector.markTempUnschedulable(account.getId(),
                                java.time.Instant.now().plusSeconds(600),
                                "OAuth token refresh temporarily failed");
                    }
                    failoverCount++;
                }

                // --- 可重试错误 (429, 529, 5xx) ---
                else if (statusCode == 429 || statusCode == 529 || statusCode >= 500) {
                    log.warn("[{}] 上游可重试错误: status={}, account_id={}, attempt={}/{}",
                            requestId, statusCode, account.getId(), failoverCount + 1, MAX_FAILOVER_SWITCHES);
                    markAccountUnhealthy(account, statusCode, upstreamResp, failoverCount);
                    concurrencyService.release(slot);
                    failoverCount++;
                }

                // --- 非重试错误（含选择性透传裁决）---
                else {
                    // 读取上游错误 body
                    String errorBody;
                    try (var input = upstreamResp.body()) {
                        errorBody = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                    }

                    ErrorPassthroughService.ErrorAction action =
                            errorPassthroughService.decide(statusCode, errorBody, accountPlatform.name());

                    if (action == ErrorPassthroughService.ErrorAction.RETRY) {
                        log.warn("[{}] 错误透传裁决 RETRY: status={}, account_id={}, attempt={}/{}",
                                requestId, statusCode, account.getId(), failoverCount + 1, MAX_FAILOVER_SWITCHES);
                        markAccountUnhealthy(account, statusCode, upstreamResp, failoverCount);
                        concurrencyService.release(slot);
                        failoverCount++;
                        // 回到 failover 循环
                    } else {
                        log.warn("[{}] 错误透传裁决 PASS: status={}, account_id={}",
                                requestId, statusCode, account.getId());
                        concurrencyService.release(slot);
                        writeMaskedUpstreamError(response, statusCode, errorBody);
                        return;
                    }
                }

            } finally {
                GatewayRequestContext.clear();
            }
        }

        log.warn("[{}] Failover 耗尽: group={}, attempts={}, max={}",
                requestId, group.getName(), failoverCount, MAX_FAILOVER_SWITCHES);
        getErrorWriter().writeError(response, 503, "overloaded_error",
                "All accounts in group '" + group.getName()
                + "' are unavailable after " + failoverCount + " attempts.");
    }

    // ========================
    // 受保护辅助方法
    // ========================

    protected GatewayResponseResult handleStreaming(HttpResponse<InputStream> upstreamResp,
                                                    HttpServletResponse response,
                                                    GatewayRequestContext ctx,
                                                    IUsageParser usageParser) throws IOException {
        return gatewayResponseService.handleStreaming(upstreamResp, response, ctx, usageParser);
    }

    /** 将上游 SSE 聚合为客户端非流式响应，适配 OpenAI OAuth Codex 仅支持上游流式的场景。 */
    protected UsageTokens handleStreamingAsNonStreaming(HttpResponse<InputStream> upstreamResp,
                                                        HttpServletResponse response,
                                                        GatewayRequestContext ctx,
                                                        IUsageParser usageParser) throws IOException {
        return gatewayResponseService.handleStreamingAsNonStreaming(upstreamResp, response, ctx, usageParser);
    }

    /** 打印 OpenAI OAuth 上游限额相关响应头，用于确认 5h/7d 窗口的真实 header 名称。 */
    private void logOpenAiOAuthQuotaHeaders(String requestId, AccountEntity account, HttpHeaders headers) {
        if (account == null || account.getPlatform() != Platform.OPENAI || account.getType() != AccountType.OAUTH
                || headers == null) {
            return;
        }
        headers.map().forEach((name, values) -> {
            String lower = name.toLowerCase();
            if (lower.contains("limit") || lower.contains("remaining") || lower.contains("reset")
                    || lower.contains("usage") || lower.contains("quota") || lower.contains("window")) {
                log.debug("[{}] OpenAI OAuth 上游限额响应头: {}={}", requestId, name, values);
            }
        });
    }

    protected UsageTokens handleNonStreaming(HttpResponse<InputStream> upstreamResp,
                                              HttpServletResponse response,
                                              IUsageParser usageParser) throws IOException {
        return gatewayResponseService.handleNonStreaming(upstreamResp, response, usageParser);
    }

    /**
     * 安全格式化上游错误 —— 不暴露原始 body，通过 {@link IErrorWriter} 输出平台格式错误。
     */
    private void writeMaskedUpstreamError(HttpServletResponse response,
                                          int upstreamStatus, String errorBody) throws IOException {
        // 日志保留完整上游错误信息 + 账户上下文，方便管理员排查
        GatewayRequestContext ctx = GatewayRequestContext.get();
        String accountInfo = "";
        if (ctx != null && ctx.getSelectedAccount() != null) {
            var acc = ctx.getSelectedAccount();
            accountInfo = String.format(", account_id=%d, account_name=%s, platform=%s",
                    acc.getId(), acc.getName(), acc.getPlatform());
        }
        log.warn("Upstream error (masked): status={}{}, body={}",
                upstreamStatus, accountInfo,
                errorBody.substring(0, Math.min(500, errorBody.length())));

        String safeMessage = errorPassthroughService.extractSafeMessage(upstreamStatus, errorBody);
        String errorCode = mapStatusToErrorCode(upstreamStatus);
        getErrorWriter().writeError(response, upstreamStatus, errorCode, safeMessage);
    }

    /** HTTP 状态码 → 网关错误类型码映射 */
    private String mapStatusToErrorCode(int statusCode) {
        return switch (statusCode) {
            case 400 -> "invalid_request_error";
            case 401 -> "authentication_error";
            case 402 -> "insufficient_quota";
            case 403 -> "permission_error";
            case 404 -> "not_found_error";
            case 422 -> "unprocessable_error";
            case 429 -> "rate_limit_error";
            case 529 -> "overloaded_error";
            default -> "upstream_error";
        };
    }

    /**
     * 根据上游错误响应标记账号健康状态，使其在冷却时间内不被 AccountSelector 选中。
     */
    private void markAccountUnhealthy(AccountEntity account, int statusCode,
                                       HttpResponse<InputStream> upstreamResp, int failoverCount) {
        var now = java.time.Instant.now();

        if (statusCode == 429) {
            var retryAfterHeader = upstreamResp.headers().firstValue("Retry-After");
            long retryAfterSecs = retryAfterHeader
                    .map(s -> {
                        try { return Long.parseLong(s); } catch (NumberFormatException e) { return 10L; }
                    })
                    .orElse(10L);
            accountSelector.markRateLimited(account.getId(), now.plusSeconds(retryAfterSecs), retryAfterHeader.isPresent());
        } else if (statusCode == 529) {
            accountSelector.markOverloaded(account.getId(), now.plusSeconds(30));
        } else if (statusCode == 503) {
            accountSelector.markTempUnschedulable(account.getId(), now.plusSeconds(60),
                    "Upstream 503 at failover=" + failoverCount);
        } else if (statusCode >= 500) {
            // 其他 5xx：连续失败才标记
            if (failoverCount >= 2) {
                accountSelector.markTempUnschedulable(account.getId(), now.plusSeconds(120),
                        "Consecutive 5xx at failover=" + failoverCount);
            }
        }
    }

    /**
     * 检查 OAUTH 账号的 credentials 中是否有 refresh_token。
     * 用于区分"无 refresh_token 永久不可恢复"和"刷新端点临时故障"。
     */
    private boolean checkHasRefreshToken(AccountEntity account) {
        try {
            var creds = JSON_MAPPER.readTree(account.getCredentials());
            return creds.has("refresh_token") && !creds.get("refresh_token").asText().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

}
