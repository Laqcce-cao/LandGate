package com.landgate.infrastructure.security.filter;

import com.landgate.infrastructure.dao.IUserDao;
import com.landgate.infrastructure.security.jwt.JwtUtils;
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
 * JWT 认证过滤器 —— 对应 Go 版本 {@code middleware/jwt_auth.go}。
 * <p>
 * 从 Authorization 头中提取 Bearer Token，校验 JWT 签名和有效期，
 * 验证用户状态和 Token 版本号，通过后将认证信息存入 SecurityContext。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtUtils jwtUtils;
    private final IUserDao userDao;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            chain.doFilter(request, response);
            return;
        }

        String token = header.substring(7);
        try {
            JwtUtils.JwtClaims claims = jwtUtils.validateToken(token);

            // Verify user still exists, is active, and token version matches
            var user = userDao.selectById(claims.userId());
            if (user == null || !user.isActive()) {
                writeError(response, 401, "User not found or disabled");
                return;
            }
            if (!claims.tokenVersion().equals(user.getTokenVersion())) {
                writeError(response, 401, "Token revoked");
                return;
            }

            // Set request attributes for controller access
            request.setAttribute("user_id", claims.userId());
            request.setAttribute("user_role", claims.role());

            // Set authentication in context
            var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + claims.role().toUpperCase()));
            var auth = new UsernamePasswordAuthenticationToken(claims, null, authorities);
            auth.setDetails(user);
            SecurityContextHolder.getContext().setAuthentication(auth);

        } catch (Exception e) {
            writeError(response, 401, "Invalid or expired token");
            return;
        }

        chain.doFilter(request, response);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // SecurityConfig.securityMatcher already restricts which paths reach this filter.
        // We only skip paths that don't have an Authorization header.
        String header = request.getHeader("Authorization");
        return header == null || !header.startsWith("Bearer ");
    }

    private void writeError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":{\"message\":\"" + message + "\"}}");
    }
}
