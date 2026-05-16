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
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.io.*;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Gemini 网关控制器 —— 处理 Google Gemini API 的代理转发。
 * <p>
 * 路由映射：POST /v1beta/models/*:generateContent 等。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class GeminiGatewayController {

    private final AccountSelector accountSelector;
    private final GetAccessTokenService getAccessTokenService;
    private final HttpUpstreamClient httpUpstreamClient;
    private final IGroupRepository groupRepository;
    private final IUserRepository userRepository;
    private final BillingDomainService billingDomainService;
    private final BalanceDomainService balanceDomainService;
    private final GeminiUsageParser geminiUsageParser;
    private final ConcurrencyService concurrencyService;

    private static final String GEMINI_API_BASE = "https://generativelanguage.googleapis.com";

    @PostMapping("/v1beta/models/{modelPath}/**")
    public void proxyGemini(@RequestBody(required = false) String body,
                            @PathVariable String modelPath,
                            HttpServletRequest request,
                            HttpServletResponse response) throws IOException {
        String fullPath = request.getServletPath();
        log.info("POST {}: modelPath={}, bodySize={}", fullPath, modelPath,
                body != null ? body.length() : 0);

        Long apiKeyId = (Long) request.getAttribute("api_key_id");
        Long userId = (Long) request.getAttribute("user_id");
        Long groupId = (Long) request.getAttribute("group_id");

        if (apiKeyId == null) {
            writeGoogleError(response, 401, "MISSING_API_KEY", "Missing API key");
            return;
        }

        GroupEntity group = null;
        if (groupId != null) {
            group = groupRepository.findById(groupId)
                    .filter(g -> g.getDeletedAt() == null)
                    .orElse(null);
        }

        if (group == null) {
            writeGoogleError(response, 403, "PERMISSION_DENIED", "No group assigned");
            return;
        }

        // Balance pre-check
        if (!balanceDomainService.hasBalance(userId)) {
            writeGoogleError(response, 402, "INSUFFICIENT_BALANCE",
                    "Insufficient balance. Please recharge your account.");
            return;
        }

        AccountEntity account = accountSelector.selectAccount(group);
        if (account == null) {
            writeGoogleError(response, 503, "UNAVAILABLE", "No available Gemini accounts");
            return;
        }

        if (!concurrencyService.tryAcquire(account.getId(), account.getConcurrency())) {
            writeGoogleError(response, 429, "RESOURCE_EXHAUSTED", "Account concurrency limit reached");
            return;
        }

        long startTime = System.currentTimeMillis();

        try {
            String token = getAccessTokenService.getAccessToken(account);
            if (token == null || token.isEmpty()) {
                writeGoogleError(response, 500, "INTERNAL", "Failed to retrieve access token");
                return;
            }

            String suffix = fullPath.replace("/v1beta/models/" + modelPath, "");
            String upstreamUrl = GEMINI_API_BASE + "/v1beta/models/" + modelPath + suffix
                    + (suffix.contains("?") ? "&" : "?") + "key=" + token;

            HttpRequest upstreamReq = HttpRequest.newBuilder()
                    .uri(URI.create(upstreamUrl))
                    .timeout(Duration.ofSeconds(120))
                    .header("Content-Type", "application/json")
                    .POST(body != null
                            ? HttpRequest.BodyPublishers.ofString(body)
                            : HttpRequest.BodyPublishers.noBody())
                    .build();

            log.debug("Gemini upstream: url={}", upstreamUrl.substring(0, Math.min(200, upstreamUrl.length())));

            HttpResponse<InputStream> upstreamResp = httpUpstreamClient.send(upstreamReq);

            int statusCode = upstreamResp.statusCode();
            long durationMs = System.currentTimeMillis() - startTime;

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
                UsageTokens usage = geminiUsageParser.parse(responseBody);
                if (usage.hasUsage()) {
                    try {
                        var log = billingDomainService.calculateAndBuildLog(
                                usage, modelPath, "GEMINI",
                                userId, apiKeyId, account.getId(), group.getId(),
                                account.getRateMultiplier(),
                                false, durationMs,
                                request.getHeader("User-Agent"),
                                request.getRemoteAddr());
                        balanceDomainService.deduct(userId, log.getActualCost());
                    } catch (Exception e) {
                        log.error("Billing/deduction failed after response sent: user_id={}, model={}",
                                userId, modelPath, e);
                    }
                }
            }

            accountSelector.updateLastUsed(account.getId());
            log.info("Gemini proxy: status={}, account_id={}", statusCode, account.getId());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            writeGoogleError(response, 500, "INTERNAL", "Request interrupted");
        } catch (IOException e) {
            log.error("Gemini upstream IO error: account_id={}", account.getId(), e);
            writeGoogleError(response, 502, "UPSTREAM_ERROR", "Failed to connect to Gemini API");
        } finally {
            concurrencyService.release(account.getId());
        }
    }

    private static void writeGoogleError(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(String.format(
                "{\"error\":{\"code\":%d,\"message\":\"%s\",\"status\":\"%s\"}}",
                status, escapeJson(message), code));
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
