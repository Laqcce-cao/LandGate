package com.landgate.infrastructure.security.filter;

import com.landgate.infrastructure.dao.IApiKeyDao;
import com.landgate.infrastructure.dao.po.ApiKeyPO;
import com.landgate.types.enums.Status;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * API Key 认证过滤器 —— 对应 Go 版本 {@code middleware/apikey_auth.go}。
 * <p>
 * 从请求头（Authorization: Bearer / x-api-key / x-goog-api-key）中提取 API Key，
 * 校验 Key 是否存在且启用，通过后将 Key 信息存入 request attribute 和 SecurityContext。
 * 仅对网关路径（/v1/*, /v1beta/* 等）生效。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private final IApiKeyDao apiKeyDao;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain chain) throws ServletException, IOException {
        String apiKey = extractApiKey(request);
        if (apiKey == null) {
            writeError(response, 401, "Missing API key");
            return;
        }

        ApiKeyPO key = apiKeyDao.selectByKey(apiKey);
        if (key == null) {
            writeError(response, 401, "Invalid API key");
            return;
        }
        if (Status.DISABLED == key.getStatus()) {
            writeError(response, 403, "API key disabled");
            return;
        }

        // Store in request attribute for downstream handlers
        request.setAttribute("api_key", key);
        request.setAttribute("api_key_id", key.getId());
        request.setAttribute("user_id", key.getUserId());
        request.setAttribute("group_id", key.getGroupId());

        // Set Spring Security Authentication so authorizeHttpRequests permits the request
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                key.getUserId(), key.getKey(), List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContextHolder.getContext().setAuthentication(auth);

        chain.doFilter(request, response);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        // Only filter gateway paths
        return !path.startsWith("/v1/")
                && !path.startsWith("/v1beta/")
                && !path.startsWith("/chat/completions")
                && !path.startsWith("/images/")
                && !path.startsWith("/responses")
                && !path.startsWith("/antigravity/")
                && !path.startsWith("/backend-api/codex/");
    }

    private String extractApiKey(HttpServletRequest request) {
        // Try Authorization: Bearer <key>
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            return auth.substring(7);
        }
        // Try x-api-key
        String xApiKey = request.getHeader("x-api-key");
        if (xApiKey != null) {
            return xApiKey;
        }
        // Try x-goog-api-key (Gemini)
        String googleKey = request.getHeader("x-goog-api-key");
        if (googleKey != null) {
            return googleKey;
        }
        return null;
    }

    private void writeError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":{\"message\":\"" + message + "\"}}");
    }
}
