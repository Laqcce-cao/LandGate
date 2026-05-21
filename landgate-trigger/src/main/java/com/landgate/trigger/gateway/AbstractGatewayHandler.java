package com.landgate.trigger.gateway;

import com.landgate.domain.account.model.entity.AccountEntity;
import com.landgate.domain.auth.adapter.repository.IUserRepository;
import com.landgate.domain.auth.model.entity.UserEntity;
import com.landgate.domain.billing.model.valobj.UsageTokens;
import com.landgate.domain.billing.service.BillingDomainService;
import com.landgate.domain.group.adapter.repository.IGroupRepository;
import com.landgate.domain.group.model.entity.GroupEntity;
import com.landgate.infrastructure.upstream.HttpUpstreamClient;
import com.landgate.types.enums.AccountType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
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
    protected final IGroupRepository groupRepository;
    protected final IUserRepository userRepository;
    protected final BillingDomainService billingDomainService;
    protected final BalanceDomainService balanceDomainService;
    protected final ConcurrencyService concurrencyService;
    protected final SessionHashService sessionHashService;
    protected final OAuthTokenRefreshService oauthTokenRefreshService;
    protected final ErrorPassthroughService errorPassthroughService;

    private static final int MAX_FAILOVER_SWITCHES = 3;
    private static final String ATTR_GATEWAY_MODEL = "gateway_model";
    private static final String ATTR_GATEWAY_UPSTREAM_PATH = "gateway_upstream_path";

    protected AbstractGatewayHandler(
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
            ErrorPassthroughService errorPassthroughService) {
        this.accountSelector = accountSelector;
        this.getAccessTokenService = getAccessTokenService;
        this.httpUpstreamClient = httpUpstreamClient;
        this.groupRepository = groupRepository;
        this.userRepository = userRepository;
        this.billingDomainService = billingDomainService;
        this.balanceDomainService = balanceDomainService;
        this.concurrencyService = concurrencyService;
        this.sessionHashService = sessionHashService;
        this.oauthTokenRefreshService = oauthTokenRefreshService;
        this.errorPassthroughService = errorPassthroughService;
    }

    // --- 子类需注入的策略钩子 ---

    protected abstract IRequestTransformer getTransformer();
    protected abstract IUsageParser getUsageParser();
    protected abstract IErrorWriter getErrorWriter();
    protected abstract String getPlatformName();

    // ========================
    // 模板方法
    // ========================

    @Override
    public void handle(String body, HttpServletRequest request,
                       HttpServletResponse response) throws IOException {
        long startTime = System.currentTimeMillis();

        // Step 1: 提取请求上下文
        Long apiKeyId = (Long) request.getAttribute("api_key_id");
        Long userId = (Long) request.getAttribute("user_id");
        Long groupId = (Long) request.getAttribute("group_id");

        if (apiKeyId == null) {
            getErrorWriter().writeError(response, 401, "authentication_error", "Missing API key");
            return;
        }

        // Step 2: 加载并校验 Group
        GroupEntity group = loadGroup(groupId);
        if (group == null) {
            getErrorWriter().writeError(response, 403, "permission_error",
                    "API key has no group assigned. Contact admin to assign a group.");
            return;
        }
        if (!group.isActive()) {
            getErrorWriter().writeError(response, 403, "permission_error",
                    "Group '" + group.getName() + "' is disabled.");
            return;
        }

        log.info("Gateway request: key_id={}, user_id={}, group_id={}, group={}, platform={}",
                apiKeyId, userId, group.getId(), group.getName(), getPlatformName());

        // Step 3: Session 粘滞
        String bodyUserId = getTransformer().extractUserId(body);
        String sessionHash = sessionHashService.generateHash(request, userId, bodyUserId);
        Long stickyAccountId = sessionHashService.getBoundAccount(sessionHash);

        // Step 4: 流式检测 + 模型名提取
        // 优先从 request attribute 读取（Gemini 场景：模型名来自 URL path）
        String model = (String) request.getAttribute(ATTR_GATEWAY_MODEL);
        if (model == null) {
            model = getTransformer().extractModel(body);
        }
        String upstreamPath = (String) request.getAttribute(ATTR_GATEWAY_UPSTREAM_PATH);
        boolean stream = getTransformer().isStreamRequest(body);
        String requestId = UUID.randomUUID().toString();

        // Step 5: 余额预检查
        UserEntity user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            getErrorWriter().writeError(response, 401, "authentication_error", "User not found");
            return;
        }
        if (!user.isPrivileged() && !balanceDomainService.hasBalance(userId)) {
            getErrorWriter().writeError(response, 402, "insufficient_balance",
                    "Insufficient balance. Please recharge your account.");
            return;
        }

        // Step 6: Failover 循环
        int failoverCount = 0;
        AccountEntity account = null;

        while (failoverCount < MAX_FAILOVER_SWITCHES) {
            account = (stickyAccountId != null)
                    ? accountSelector.getById(stickyAccountId)
                    : accountSelector.selectAccount(group, model);

            if (account == null) {
                account = accountSelector.selectAccount(group, model);
            }
            stickyAccountId = null;

            if (account == null) {
                getErrorWriter().writeError(response, 503, "overloaded_error",
                        "No available accounts in group '" + group.getName() + "'.");
                return;
            }

            if (!concurrencyService.tryAcquire(account.getId(), account.getConcurrency())) {
                log.warn("Concurrency slot unavailable: account_id={}, failover={}",
                        account.getId(), failoverCount);
                failoverCount++;
                continue;
            }

            String accessToken = getAccessTokenService.getAccessToken(account);
            if (accessToken == null || accessToken.isEmpty()) {
                concurrencyService.release(account.getId());
                failoverCount++;
                continue;
            }

            var ctx = GatewayRequestContext.builder()
                    .requestId(requestId).apiKeyId(apiKeyId).userId(userId)
                    .group(group).selectedAccount(account)
                    .stream(stream).requestedModel(model)
                    .upstreamPath(upstreamPath)
                    .build();
            GatewayRequestContext.set(ctx);

            try {
                HttpRequest upstreamReq;
                try {
                    upstreamReq = getTransformer().buildUpstreamRequest(body, account, accessToken);
                } catch (Exception e) {
                    log.error("Failed to build upstream request: account_id={}", account.getId(), e);
                    concurrencyService.release(account.getId());
                    failoverCount++;
                    continue;
                }

                log.info("Forwarding to upstream: request_id={}, account_id={}, model={}, stream={}, attempt={}",
                        requestId, account.getId(), model, stream, failoverCount);

                HttpResponse<InputStream> upstreamResp;
                try {
                    upstreamResp = httpUpstreamClient.send(upstreamReq);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    concurrencyService.release(account.getId());
                    getErrorWriter().writeError(response, 502, "upstream_error", "Request interrupted");
                    return;
                } catch (IOException e) {
                    log.error("Upstream IO error: account_id={}, failover={}", account.getId(), failoverCount, e);
                    concurrencyService.release(account.getId());
                    failoverCount++;
                    continue;
                }

                int statusCode = upstreamResp.statusCode();
                long durationMs = System.currentTimeMillis() - startTime;
                log.info("Upstream response: request_id={}, status={}, elapsed={}ms, account_id={}",
                        requestId, statusCode, durationMs, account.getId());

                // --- 成功 (2xx) ---
                if (statusCode >= 200 && statusCode < 300) {
                    sessionHashService.bindSession(sessionHash, account.getId());

                    UsageTokens usage;
                    if (stream) {
                        usage = handleStreaming(upstreamResp, response, ctx);
                    } else {
                        usage = handleNonStreaming(upstreamResp, response);
                    }

                    if (usage != null && usage.hasUsage()) {
                        try {
                            var logEntry = billingDomainService.calculateAndBuildLog(
                                    usage, model, getPlatformName(),
                                    userId, apiKeyId, account.getId(), group.getId(),
                                    account.getRateMultiplier(),
                                    stream, durationMs,
                                    request.getHeader("User-Agent"),
                                    request.getRemoteAddr());
                            if (!user.isPrivileged()) {
                                balanceDomainService.deduct(userId, logEntry.getActualCost());
                            }
                        } catch (Exception e) {
                            log.error("Billing/deduction failed after response sent: user_id={}, model={}",
                                    userId, model, e);
                        }
                    }

                    accountSelector.updateLastUsed(account.getId());
                    concurrencyService.release(account.getId());
                    return;
                }

                // --- 401 OAuth 刷新 ---
                else if (statusCode == 401 && account.getType() == AccountType.OAUTH) {
                    log.info("OAuth 401 detected, attempting token refresh: account_id={}", account.getId());
                    concurrencyService.release(account.getId());
                    String newToken = oauthTokenRefreshService.refreshAccessToken(account.getId());
                    if (newToken != null) {
                        log.info("OAuth token refreshed: account_id={}, retrying", account.getId());
                        stickyAccountId = account.getId();
                        continue;
                    }
                    log.warn("OAuth token refresh failed: account_id={}, failing over", account.getId());
                    failoverCount++;
                }

                // --- 可重试错误 (429, 529, 5xx) ---
                else if (statusCode == 429 || statusCode == 529 || statusCode >= 500) {
                    log.warn("Failover error: status={}, account_id={}, attempt={}",
                            statusCode, account.getId(), failoverCount);
                    markAccountUnhealthy(account, statusCode, upstreamResp, failoverCount);
                    concurrencyService.release(account.getId());
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
                            errorPassthroughService.decide(statusCode, errorBody, getPlatformName());

                    if (action == ErrorPassthroughService.ErrorAction.RETRY) {
                        log.warn("Error passthrough RETRY: status={}, account_id={}, attempt={}",
                                statusCode, account.getId(), failoverCount);
                        markAccountUnhealthy(account, statusCode, upstreamResp, failoverCount);
                        concurrencyService.release(account.getId());
                        failoverCount++;
                        // 回到 failover 循环
                    } else {
                        concurrencyService.release(account.getId());
                        writeMaskedUpstreamError(response, statusCode, errorBody);
                        return;
                    }
                }

            } finally {
                GatewayRequestContext.clear();
            }
        }

        getErrorWriter().writeError(response, 503, "overloaded_error",
                "All accounts in group '" + group.getName()
                + "' are unavailable after " + failoverCount + " attempts.");
    }

    // ========================
    // 受保护辅助方法
    // ========================

    protected GroupEntity loadGroup(Long groupId) {
        if (groupId == null) return null;
        return groupRepository.findById(groupId)
                .filter(g -> g.getDeletedAt() == null)
                .orElse(null);
    }

    protected UsageTokens handleStreaming(HttpResponse<InputStream> upstreamResp,
                                           HttpServletResponse response,
                                           GatewayRequestContext ctx) throws IOException {
        response.setStatus(200);
        response.setContentType("text/event-stream");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("Connection", "keep-alive");
        response.setHeader("X-Accel-Buffering", "no");

        UsageTokens totalUsage = UsageTokens.builder().build();

        try (var upstreamInput = upstreamResp.body();
             var reader = new BufferedReader(new InputStreamReader(upstreamInput, StandardCharsets.UTF_8));
             var writer = response.getWriter()) {

            String line;
            while ((line = reader.readLine()) != null) {
                writer.write(line);
                writer.write("\n");

                if (line.startsWith("data: ")) {
                    String json = line.substring(6);
                    UsageTokens eventUsage = getUsageParser().parseSSELine(json);
                    if (eventUsage != null) {
                        totalUsage.merge(eventUsage);
                    }
                }

                if (getUsageParser().isStreamDone(line)) {
                    writer.flush();
                    break;
                }

                writer.flush();
            }
        } catch (IOException e) {
            if (response.isCommitted()) {
                log.debug("Client disconnected during SSE stream: request_id={}", ctx.getRequestId());
            } else {
                log.warn("SSE stream error", e);
                throw e;
            }
        }

        log.debug("Stream usage: request_id={}, input={}, output={}, cache_w={}, cache_r={}",
                ctx.getRequestId(),
                totalUsage.getInputTokens(), totalUsage.getOutputTokens(),
                totalUsage.getCacheCreationTokens(), totalUsage.getCacheReadTokens());
        return totalUsage;
    }

    protected UsageTokens handleNonStreaming(HttpResponse<InputStream> upstreamResp,
                                              HttpServletResponse response) throws IOException {
        response.setStatus(upstreamResp.statusCode());
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        String responseBody;
        try (var input = upstreamResp.body()) {
            responseBody = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        try (var output = response.getOutputStream()) {
            output.write(responseBody.getBytes(StandardCharsets.UTF_8));
            output.flush();
        }

        return getUsageParser().parseNonStreaming(responseBody);
    }

    /**
     * 安全格式化上游错误 —— 不暴露原始 body，通过 {@link IErrorWriter} 输出平台格式错误。
     */
    private void writeMaskedUpstreamError(HttpServletResponse response,
                                          int upstreamStatus, String errorBody) throws IOException {
        log.warn("Upstream error (masked): status={}, body={}",
                upstreamStatus, errorBody.substring(0, Math.min(500, errorBody.length())));

        String safeMessage = errorPassthroughService.extractSafeMessage(upstreamStatus, errorBody);
        String errorCode = mapStatusToErrorCode(upstreamStatus);
        getErrorWriter().writeError(response, upstreamStatus, errorCode, safeMessage);
    }

    /**
     * @deprecated 使用 {@link #writeMaskedUpstreamError} 替代，避免盲透传上游 body。
     * 保留以兼容子类自定义调用。
     */
    @Deprecated
    protected void handleUpstreamError(HttpResponse<InputStream> upstreamResp,
                                        HttpServletResponse response,
                                        int upstreamStatus) throws IOException {
        String errorBody;
        try (var input = upstreamResp.body()) {
            errorBody = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        writeMaskedUpstreamError(response, upstreamStatus, errorBody);
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
            long retryAfterSecs = upstreamResp.headers()
                    .firstValue("Retry-After")
                    .map(s -> {
                        try { return Long.parseLong(s); } catch (NumberFormatException e) { return 60L; }
                    })
                    .orElse(60L);
            accountSelector.markRateLimited(account.getId(), now.plusSeconds(retryAfterSecs));
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
}
