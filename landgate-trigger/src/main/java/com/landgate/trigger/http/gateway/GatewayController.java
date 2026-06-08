package com.landgate.trigger.http.gateway;

import com.landgate.infrastructure.dao.IUserDao;
import com.landgate.infrastructure.dao.po.ApiKeyPO;
import com.landgate.infrastructure.dao.po.UserPO;
import com.landgate.trigger.gateway.GatewayDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 网关统一入口 —— 处理所有 AI API 代理转发请求。
 * <p>
 * URL 路径决定协议格式（Anthropic / OpenAI / Gemini），
 * 由 {@link GatewayDispatcher} 根据路径选择对应的 Handler。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class GatewayController {

    private final GatewayDispatcher dispatcher;
    private final IUserDao userDao;

    private static final String ATTR_GATEWAY_MODEL = "gateway_model";
    private static final String ATTR_GATEWAY_UPSTREAM_PATH = "gateway_upstream_path";
    private static final String ATTR_REQUEST_ID = "gateway_request_id";

    // ---- Anthropic Messages API ----

    @PostMapping("/v1/messages")
    public void messages(@RequestBody String body,
                         HttpServletRequest request,
                         HttpServletResponse response) throws IOException {
        String requestId = UUID.randomUUID().toString();
        request.setAttribute(ATTR_REQUEST_ID, requestId);
        log.info("[{}] => POST /v1/messages | content_length={} | remote_addr={} | ua={}",
                requestId, body != null ? body.length() : 0,
                request.getRemoteAddr(), request.getHeader("User-Agent"));
        dispatcher.dispatch(request, response, body);
    }

    @PostMapping("/v1/messages/count_tokens")
    public void countTokens(@RequestBody String body,
                            HttpServletRequest request,
                            HttpServletResponse response) throws IOException {
        log.info("POST /v1/messages/count_tokens");
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(
                "{\"type\":\"error\",\"error\":{\"type\":\"not_implemented\",\"message\":\"count_tokens is not yet implemented\"}}");
    }

    @GetMapping("/v1/models")
    public void models(HttpServletRequest request, HttpServletResponse response) throws IOException {
        log.info("GET /v1/models");
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"data\":[],\"has_more\":false,\"first_id\":null,\"last_id\":null}");
    }

    /**
     * cc switch 用量查询入口。
     * <p>
     * API Key 鉴权由 {@code ApiKeyAuthFilter} 完成，这里只负责返回 cc switch
     * usageScript 能识别的余额字段：remaining / quota.remaining / balance / unit。
     */
    @GetMapping("/v1/usage")
    public ResponseEntity<?> usage(HttpServletRequest request) {
        ApiKeyPO apiKey = (ApiKeyPO) request.getAttribute("api_key");
        Long userId = (Long) request.getAttribute("user_id");
        if (apiKey == null || userId == null) {
            return ResponseEntity.status(401).body(Map.of(
                    "error", Map.of("message", "Missing API key")
            ));
        }

        boolean active = apiKey.isActive();
        BigDecimal quota = valueOrZero(apiKey.getQuota());
        BigDecimal quotaUsed = valueOrZero(apiKey.getQuotaUsed());
        if (quota.signum() > 0) {
            BigDecimal remaining = quota.subtract(quotaUsed).max(BigDecimal.ZERO);
            Map<String, Object> resp = baseUsageResponse("quota_limited", active, apiKey.getStatus() != null ? apiKey.getStatus().getKey() : null);
            resp.put("remaining", remaining);
            resp.put("unit", "USD");
            resp.put("quota", Map.of(
                    "limit", quota,
                    "used", quotaUsed,
                    "remaining", remaining,
                    "unit", "USD"
            ));
            putExpiresAt(resp, apiKey.getExpiresAt());
            return ResponseEntity.ok(resp);
        }

        UserPO user = userDao.selectById(userId);
        if (user == null) {
            return ResponseEntity.status(404).body(Map.of(
                    "error", Map.of("message", "User not found")
            ));
        }

        BigDecimal balance = valueOrZero(user.getBalance());
        Map<String, Object> resp = baseUsageResponse("unrestricted", active, apiKey.getStatus() != null ? apiKey.getStatus().getKey() : null);
        resp.put("planName", "钱包余额");
        resp.put("remaining", balance);
        resp.put("balance", balance);
        resp.put("unit", "USD");
        putExpiresAt(resp, apiKey.getExpiresAt());
        return ResponseEntity.ok(resp);
    }

    // ---- OpenAI Chat Completions API ----

    @PostMapping("/v1/chat/completions")
    public void chatCompletions(@RequestBody String body,
                                HttpServletRequest request,
                                HttpServletResponse response) throws IOException {
        String requestId = UUID.randomUUID().toString();
        request.setAttribute(ATTR_REQUEST_ID, requestId);
        log.info("[{}] => POST /v1/chat/completions | content_length={} | remote_addr={} | ua={}",
                requestId, body != null ? body.length() : 0,
                request.getRemoteAddr(), request.getHeader("User-Agent"));

        Long groupId = (Long) request.getAttribute("group_id");
        if (groupId == null) {
            log.warn("[{}] 缺少 API Key 认证 (group_id=null)，返回 401", requestId);
            response.setStatus(401);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(
                    "{\"error\":{\"message\":\"Missing API key\",\"type\":\"authentication_error\",\"param\":null,\"code\":null}}");
            return;
        }
        log.info("[{}] 认证通过: group_id={}", requestId, groupId);

        dispatcher.dispatch(request, response, body);
    }

    // ---- OpenAI Responses API ----

    @PostMapping({"/v1/responses", "/responses", "/backend-api/codex/responses"})
    public void responses(@RequestBody String body,
                          HttpServletRequest request,
                          HttpServletResponse response) throws IOException {
        handleResponses(body, request, response);
    }

    @PostMapping({"/v1/responses/**", "/responses/**", "/backend-api/codex/responses/**"})
    public void responsesSubpath(@RequestBody String body,
                                 HttpServletRequest request,
                                 HttpServletResponse response) throws IOException {
        handleResponses(body, request, response);
    }

    /** 处理 OpenAI Responses 与 Codex CLI 兼容入口。 */
    private void handleResponses(String body,
                                 HttpServletRequest request,
                                 HttpServletResponse response) throws IOException {
        String requestId = UUID.randomUUID().toString();
        request.setAttribute(ATTR_REQUEST_ID, requestId);
        log.info("[{}] => POST {} | content_length={} | remote_addr={} | ua={}",
                requestId, request.getServletPath(), body != null ? body.length() : 0,
                request.getRemoteAddr(), request.getHeader("User-Agent"));

        Long groupId = (Long) request.getAttribute("group_id");
        if (groupId == null) {
            log.warn("[{}] 缺少 API Key 认证 (group_id=null)，返回 401", requestId);
            response.setStatus(401);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(
                    "{\"error\":{\"message\":\"Missing API key\",\"type\":\"authentication_error\",\"param\":null,\"code\":null}}");
            return;
        }
        log.info("[{}] 认证通过: group_id={}", requestId, groupId);

        request.setAttribute(ATTR_GATEWAY_UPSTREAM_PATH, canonicalResponsesPath(request.getServletPath()));
        dispatcher.dispatch(request, response, body);
    }

    private static Map<String, Object> baseUsageResponse(String mode, boolean active, String status) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("mode", mode);
        resp.put("isValid", active);
        resp.put("is_active", active);
        if (status != null) {
            resp.put("status", status);
        }
        return resp;
    }

    private static BigDecimal valueOrZero(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    private static void putExpiresAt(Map<String, Object> resp, Instant expiresAt) {
        if (expiresAt != null) {
            resp.put("expires_at", expiresAt);
        }
    }

    private static String canonicalResponsesPath(String servletPath) {
        if (servletPath == null || servletPath.isBlank()) {
            return "/v1/responses";
        }
        if (servletPath.startsWith("/v1/responses")) {
            return servletPath;
        }
        if (servletPath.startsWith("/responses")) {
            return "/v1" + servletPath;
        }
        if (servletPath.startsWith("/backend-api/codex/responses")) {
            return servletPath;
        }
        return "/v1/responses";
    }

    // ---- Gemini API ----

    @PostMapping("/v1beta/models/{modelPath}/**")
    public void proxyGemini(@RequestBody(required = false) String body,
                            @PathVariable String modelPath,
                            HttpServletRequest request,
                            HttpServletResponse response) throws IOException {
        String requestId = UUID.randomUUID().toString();
        request.setAttribute(ATTR_REQUEST_ID, requestId);
        String fullPath = request.getServletPath();
        log.info("[{}] => POST {} | modelPath={} | bodySize={} | ua={}",
                requestId, fullPath, modelPath,
                body != null ? body.length() : 0, request.getHeader("User-Agent"));

        // 将 Gemini 特有的路径信息注入 request attribute
        request.setAttribute(ATTR_GATEWAY_MODEL, modelPath);
        request.setAttribute(ATTR_GATEWAY_UPSTREAM_PATH, fullPath);

        Long groupId = (Long) request.getAttribute("group_id");
        if (groupId == null) {
            log.warn("[{}] 缺少 API Key 认证 (group_id=null)，返回 401", requestId);
            writeGoogleError(response, 401, "MISSING_API_KEY", "Missing API key");
            return;
        }
        log.info("[{}] 认证通过: group_id={}", requestId, groupId);

        dispatcher.dispatch(request, response, body);
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
