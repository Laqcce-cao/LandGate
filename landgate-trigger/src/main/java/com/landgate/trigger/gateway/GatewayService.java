package com.landgate.trigger.gateway;

import com.landgate.domain.account.model.entity.AccountEntity;
import com.landgate.domain.auth.adapter.repository.IUserRepository;
import com.landgate.domain.auth.model.entity.UserEntity;
import com.landgate.domain.billing.model.valobj.ClaudeUsageVO;
import com.landgate.domain.billing.service.BillingDomainService;
import com.landgate.domain.group.adapter.repository.IGroupRepository;
import com.landgate.domain.group.model.entity.GroupEntity;
import com.landgate.infrastructure.upstream.HttpUpstreamClient;
import com.landgate.types.enums.AccountType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.*;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 网关核心服务 —— 协调上游 AI API 请求的完整处理流程。
 * <p>
 * 流程：验证 API Key / 分组 → 余额预检查 → 会话粘滞 → 账号选择（带故障转移）→
 * 构建上游请求 → 转发 → 解析用量 → 计费扣减。
 * <p>
 * 支持流式（SSE）和非流式两种响应模式，最多 3 次故障转移切换。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GatewayService {

    private final AccountSelector accountSelector;
    private final AnthropicTransformer transformer;
    private final GetAccessTokenService getAccessTokenService;
    private final HttpUpstreamClient httpUpstreamClient;
    private final IGroupRepository groupRepository;
    private final IUserRepository userRepository;
    private final BillingDomainService billingDomainService;
    private final BalanceDomainService balanceDomainService;
    private final UsageParser usageParser;
    private final ConcurrencyService concurrencyService;
    private final SessionHashService sessionHashService;
    private final OAuthTokenRefreshService oauthTokenRefreshService;

    private static final int MAX_FAILOVER_SWITCHES = 3;

    public void handleMessages(String body, HttpServletRequest request, HttpServletResponse response) throws IOException {
        long startTime = System.currentTimeMillis();

        // Step 1: Parse request context from filter attributes
        Long apiKeyId = (Long) request.getAttribute("api_key_id");
        Long userId = (Long) request.getAttribute("user_id");
        Long groupId = (Long) request.getAttribute("group_id");

        if (apiKeyId == null) {
            writeAnthropicError(response, 401, "authentication_error", "Missing API key");
            return;
        }

        // Step 2: Get group
        GroupEntity group = null;
        if (groupId != null) {
            group = groupRepository.findById(groupId)
                    .filter(g -> g.getDeletedAt() == null)
                    .orElse(null);
        }

        if (group == null) {
            writeAnthropicError(response, 403, "permission_error",
                    "API key has no group assigned. Contact admin to assign a group.");
            return;
        }

        if (!group.isActive()) {
            writeAnthropicError(response, 403, "permission_error",
                    "Group '" + group.getName() + "' is disabled.");
            return;
        }

        log.info("Gateway request: key_id={}, user_id={}, group_id={}, group={}, platform={}",
                apiKeyId, userId, group.getId(), group.getName(), group.getPlatform());

        // Step 3: Session stickiness
        String sessionHash = sessionHashService.generateHash(request, apiKeyId);
        Long stickyAccountId = sessionHashService.getBoundAccount(sessionHash);

        // Step 4: Stream detection
        boolean stream = transformer.isStreamRequest(body);
        String requestId = UUID.randomUUID().toString();
        String model = transformer.extractModel(body);

        // Step 4.5: Balance pre-check — reject zero-balance users before upstream call
        UserEntity user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            writeAnthropicError(response, 401, "authentication_error", "User not found");
            return;
        }
        if (!balanceDomainService.hasBalance(userId)) {
            writeAnthropicError(response, 402, "insufficient_balance",
                    "Insufficient balance. Please recharge your account.");
            return;
        }

        // Step 5: Failover loop
        int failoverCount = 0;
        AccountEntity account = null;

        while (failoverCount < MAX_FAILOVER_SWITCHES) {
            account = (stickyAccountId != null)
                    ? accountSelector.getById(stickyAccountId)
                    : accountSelector.selectAccount(group);

            if (account == null) {
                account = accountSelector.selectAccount(group);
            }
            stickyAccountId = null;

            if (account == null) {
                writeAnthropicError(response, 503, "overloaded_error",
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
                    .requestId(requestId)
                    .apiKeyId(apiKeyId)
                    .userId(userId)
                    .group(group)
                    .selectedAccount(account)
                    .stream(stream)
                    .requestedModel(model)
                    .build();
            GatewayRequestContext.set(ctx);

            try {
                HttpRequest upstreamReq;
                try {
                    upstreamReq = transformer.buildUpstreamRequest(body, account, accessToken);
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
                    writeAnthropicError(response, 502, "upstream_error", "Request interrupted");
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

                if (statusCode >= 200 && statusCode < 300) {
                    sessionHashService.bindSession(sessionHash, account.getId());

                    ClaudeUsageVO usage;
                    if (stream) {
                        usage = handleStreamingResponse(upstreamResp, response, ctx);
                    } else {
                        usage = handleNonStreamingResponse(upstreamResp, response);
                    }

                    // Bill and deduct after response written (best-effort, allows negative)
                    if (usage != null && usage.hasUsage()) {
                        try {
                            var log = billingDomainService.calculateAndBuildLog(
                                    usage, model,
                                    userId, apiKeyId, account.getId(), group.getId(),
                                    account.getRateMultiplier(),
                                    stream, durationMs,
                                    request.getHeader("User-Agent"),
                                    request.getRemoteAddr());

                            balanceDomainService.deduct(userId, log.getActualCost());
                        } catch (Exception e) {
                            log.error("Billing/deduction failed after response sent: user_id={}, model={}",
                                    userId, model, e);
                        }
                    }

                    accountSelector.updateLastUsed(account.getId());
                    concurrencyService.release(account.getId());
                    return;

                } else if (statusCode == 401 && account.getType() == AccountType.OAUTH) {
                    // OAuth token may have expired — attempt refresh and retry
                    log.info("OAuth 401 detected, attempting token refresh: account_id={}",
                            account.getId());
                    concurrencyService.release(account.getId());
                    String newToken = oauthTokenRefreshService.refreshAccessToken(account.getId());
                    if (newToken != null) {
                        log.info("OAuth token refreshed successfully: account_id={}, retrying", account.getId());
                        stickyAccountId = account.getId();
                        continue;
                    }
                    log.warn("OAuth token refresh failed: account_id={}, failing over", account.getId());
                    failoverCount++;
                } else if (statusCode == 429 || statusCode == 529 || statusCode >= 500) {
                    log.warn("Failover error: status={}, account_id={}, attempt={}",
                            statusCode, account.getId(), failoverCount);
                    concurrencyService.release(account.getId());
                    failoverCount++;
                } else {
                    concurrencyService.release(account.getId());
                    handleUpstreamError(upstreamResp, response, statusCode);
                    return;
                }

            } finally {
                GatewayRequestContext.clear();
            }
        }

        writeAnthropicError(response, 503, "overloaded_error",
                "All accounts in group '" + group.getName() + "' are unavailable after " + failoverCount + " attempts.");
    }

    private ClaudeUsageVO handleStreamingResponse(HttpResponse<InputStream> upstreamResp,
                                                   HttpServletResponse response,
                                                   GatewayRequestContext ctx) throws IOException {
        response.setStatus(200);
        response.setContentType("text/event-stream");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("Connection", "keep-alive");
        response.setHeader("X-Accel-Buffering", "no");

        ClaudeUsageVO totalUsage = new ClaudeUsageVO();

        try (var upstreamInput = upstreamResp.body();
             var reader = new BufferedReader(new InputStreamReader(upstreamInput, StandardCharsets.UTF_8));
             var writer = response.getWriter()) {

            String line;
            long firstTokenMs = 0;
            boolean firstData = false;

            while ((line = reader.readLine()) != null) {
                writer.write(line);
                writer.write("\n");

                if (!firstData && line.startsWith("data: ") && !line.contains("\"type\":\"ping\"")) {
                    firstTokenMs = System.currentTimeMillis();
                    firstData = true;
                }

                if (line.startsWith("data: ")) {
                    String json = line.substring(6);
                    ClaudeUsageVO eventUsage = usageParser.parseSSELine(json);
                    if (eventUsage != null) {
                        totalUsage.mergeNonZero(eventUsage);
                    }
                }

                if (line.equals("event: message_stop") || line.equals("data: [DONE]")) {
                    writer.flush();
                    break;
                }

                writer.flush();
            }

            if (firstTokenMs > 0) {
                log.debug("SSE stream: first_token_ms={}", firstTokenMs);
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

    private ClaudeUsageVO handleNonStreamingResponse(HttpResponse<InputStream> upstreamResp,
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

        return usageParser.parseNonStreaming(responseBody);
    }

    private void handleUpstreamError(HttpResponse<InputStream> upstreamResp,
                                      HttpServletResponse response,
                                      int upstreamStatus) throws IOException {
        String errorBody;
        try (var input = upstreamResp.body()) {
            errorBody = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        log.warn("Upstream error: status={}, body={}", upstreamStatus, errorBody.substring(0, Math.min(500, errorBody.length())));

        if (upstreamStatus == 429) {
            response.setStatus(429);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\",\"message\":\"Rate limited by upstream API\"}}");
            return;
        }

        if (upstreamStatus == 529) {
            response.setStatus(503);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Upstream API is overloaded\"}}");
            return;
        }

        response.setStatus(upstreamStatus);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(errorBody);
    }

    public static void writeAnthropicError(HttpServletResponse response, int status, String errorType, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        String json = String.format(
                "{\"type\":\"error\",\"error\":{\"type\":\"%s\",\"message\":\"%s\"}}",
                errorType, escapeJson(message));
        response.getWriter().write(json);
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
