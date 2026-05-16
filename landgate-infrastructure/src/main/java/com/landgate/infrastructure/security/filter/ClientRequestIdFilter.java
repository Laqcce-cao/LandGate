package com.landgate.infrastructure.security.filter;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 客户端请求 ID 过滤器 —— 为每个网关请求分配唯一 UUID。
 * <p>
 * 对应 Go 版本 {@code middleware.ClientRequestID}。
 * <p>
 * 优先使用客户端传入的 X-Request-Id，否则生成新的 UUID。
 * 响应头中设置 X-Request-Id 方便客户端追踪。
 */
@Slf4j
@Component
public class ClientRequestIdFilter extends OncePerRequestFilter {

    private static final String HEADER_NAME = "X-Request-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain chain) throws ServletException, IOException {
        String requestId = request.getHeader(HEADER_NAME);
        if (requestId == null || requestId.isEmpty()) {
            requestId = UUID.randomUUID().toString();
        }

        // 设置到 request attribute 和 response header
        request.setAttribute("request_id", requestId);
        response.setHeader(HEADER_NAME, requestId);

        chain.doFilter(request, response);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return !path.startsWith("/v1/")
                && !path.startsWith("/v1beta/")
                && !path.startsWith("/chat/")
                && !path.startsWith("/images/")
                && !path.startsWith("/responses")
                && !path.startsWith("/antigravity/");
    }
}
