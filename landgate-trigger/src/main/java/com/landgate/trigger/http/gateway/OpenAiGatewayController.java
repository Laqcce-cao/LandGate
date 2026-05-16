package com.landgate.trigger.http.gateway;

import com.landgate.domain.account.model.entity.AccountEntity;
import com.landgate.domain.auth.adapter.repository.IUserRepository;
import com.landgate.domain.billing.model.valobj.UsageTokens;
import com.landgate.domain.billing.service.BillingDomainService;
import com.landgate.trigger.gateway.BalanceDomainService;
import com.landgate.domain.group.adapter.repository.IGroupRepository;
import com.landgate.domain.group.model.entity.GroupEntity;
import com.landgate.trigger.gateway.*;
import com.landgate.infrastructure.upstream.HttpUpstreamClient;
import com.landgate.types.enums.Platform;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.io.*;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

/**
 * OpenAI 网关控制器 —— 处理 OpenAI Chat Completions API 的代理转发。
 * <p>
 * 路由映射：POST /chat/completions, /v1/chat/completions, /v1/images/generations 等。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class OpenAiGatewayController {

    private final GatewayService gatewayService;
    private final OpenAiTransformer openAiTransformer;
    private final AccountSelector accountSelector;
    private final GetAccessTokenService getAccessTokenService;
    private final HttpUpstreamClient httpUpstreamClient;
    private final IGroupRepository groupRepository;
    private final IUserRepository userRepository;
    private final BillingDomainService billingDomainService;
    private final BalanceDomainService balanceDomainService;
    private final OpenAiUsageParser openAiUsageParser;
    private final ConcurrencyService concurrencyService;

    @PostMapping("/v1/chat/completions")
    public void chatCompletions(@RequestBody String body,
                                 HttpServletRequest request,
                                 HttpServletResponse response) throws IOException {
        log.info("POST /v1/chat/completions");

        Long apiKeyId = (Long) request.getAttribute("api_key_id");
        Long groupId = (Long) request.getAttribute("group_id");

        if (apiKeyId == null) {
            GatewayService.writeAnthropicError(response, 401, "authentication_error", "Missing API key");
            return;
        }

        GroupEntity group = null;
        if (groupId != null) {
            group = groupRepository.findById(groupId)
                    .filter(g -> g.getDeletedAt() == null)
                    .orElse(null);
        }

        if (group == null) {
            GatewayService.writeAnthropicError(response, 403, "permission_error",
                    "API key has no group assigned.");
            return;
        }

        if (Platform.OPENAI == group.getPlatform()) {
            forwardToOpenAi(body, request, response, group);
        } else {
            gatewayService.handleMessages(body, request, response);
        }
    }

    private void forwardToOpenAi(String body, HttpServletRequest request,
                                  HttpServletResponse response,
                                  GroupEntity group) throws IOException {
        long startTime = System.currentTimeMillis();
        Long userId = (Long) request.getAttribute("user_id");
        Long apiKeyId = (Long) request.getAttribute("api_key_id");

        // Balance pre-check
        if (!balanceDomainService.hasBalance(userId)) {
            GatewayService.writeAnthropicError(response, 402, "insufficient_balance",
                    "Insufficient balance. Please recharge your account.");
            return;
        }

        AccountEntity account = accountSelector.selectAccount(group);
        if (account == null) {
            GatewayService.writeAnthropicError(response, 503, "overloaded_error",
                    "No available OpenAI accounts.");
            return;
        }

        if (!concurrencyService.tryAcquire(account.getId(), account.getConcurrency())) {
            GatewayService.writeAnthropicError(response, 503, "overloaded_error",
                    "Account concurrency limit reached.");
            return;
        }

        try {
            String token = getAccessTokenService.getAccessToken(account);
            if (token == null || token.isEmpty()) {
                GatewayService.writeAnthropicError(response, 502, "upstream_error",
                        "Failed to retrieve access token.");
                return;
            }

            HttpRequest upstreamReq = openAiTransformer.buildChatCompletionsRequest(body, account, token);

            HttpResponse<InputStream> upstreamResp;
            try {
                upstreamResp = httpUpstreamClient.send(upstreamReq);
            } catch (Exception e) {
                log.error("OpenAI upstream error: account_id={}", account.getId(), e);
                GatewayService.writeAnthropicError(response, 502, "upstream_error",
                        "Failed to connect to OpenAI upstream.");
                return;
            }

            int statusCode = upstreamResp.statusCode();
            long durationMs = System.currentTimeMillis() - startTime;
            log.info("OpenAI upstream response: status={}, elapsed={}ms, account_id={}",
                    statusCode, durationMs, account.getId());

            // Buffer response body for billing
            byte[] responseBytes;
            try (var input = upstreamResp.body()) {
                responseBytes = input.readAllBytes();
            }
            String responseBody = new String(responseBytes, StandardCharsets.UTF_8);

            // Write response first — user always gets the result
            response.setStatus(statusCode);
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            try (var output = response.getOutputStream()) {
                output.write(responseBytes);
                output.flush();
            }

            // Bill and deduct after response written (best-effort, allows negative)
            if (statusCode >= 200 && statusCode < 300) {
                UsageTokens usage = openAiUsageParser.parseNonStreaming(responseBody);
                if (usage.hasUsage()) {
                    try {
                        String model = openAiUsageParser.extractModel(body);
                        var log = billingDomainService.calculateAndBuildLog(
                                usage, model, "OPENAI",
                                userId, apiKeyId, account.getId(), group.getId(),
                                account.getRateMultiplier(),
                                false, durationMs,
                                request.getHeader("User-Agent"),
                                request.getRemoteAddr());
                        balanceDomainService.deduct(userId, log.getActualCost());
                    } catch (Exception e) {
                        log.error("Billing/deduction failed after response sent: user_id={}, model={}",
                                userId, openAiUsageParser.extractModel(body), e);
                    }
                }
            }

            accountSelector.updateLastUsed(account.getId());
        } finally {
            concurrencyService.release(account.getId());
        }
    }
}
