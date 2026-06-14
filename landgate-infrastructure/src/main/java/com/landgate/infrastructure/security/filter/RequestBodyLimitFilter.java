package com.landgate.infrastructure.security.filter;

import com.landgate.types.gateway.GatewayPathPolicy;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 请求体大小限制过滤器 —— 限制上传请求体的最大字节数。
 * <p>
 * 对应 Go 版本 {@code middleware.RequestBodyLimit}（基于 http.MaxBytesReader）。
 * <p>
 * 超出限制返回 413 Payload Too Large。
 */
@Slf4j
@Component
public class RequestBodyLimitFilter extends OncePerRequestFilter {

    /** 最大请求体大小（字节），默认 64MB。 */
    @Value("${landgate.gateway.max-body-size:67108864}")
    private long maxBodySize;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain chain) throws ServletException, IOException {
        // 检查 Content-Length 头（快速拒绝）
        String contentLengthHeader = request.getHeader("Content-Length");
        if (contentLengthHeader != null) {
            try {
                long contentLength = Long.parseLong(contentLengthHeader);
                if (contentLength > maxBodySize) {
                    log.warn("Request body too large: content_length={}, max={}", contentLength, maxBodySize);
                    write413(response);
                    return;
                }
            } catch (NumberFormatException e) {
                // 忽略格式错误的 Content-Length
            }
        }

        chain.doFilter(request, response);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return !GatewayPathPolicy.isGatewayPath(path);
    }

    private void write413(HttpServletResponse response) throws IOException {
        response.setStatus(413);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(
                "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"Request body too large\"}}");
    }
}
