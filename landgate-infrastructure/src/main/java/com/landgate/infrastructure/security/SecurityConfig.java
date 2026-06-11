package com.landgate.infrastructure.security;

import com.landgate.infrastructure.security.filter.ApiKeyAuthFilter;
import com.landgate.infrastructure.security.filter.ClientRequestIdFilter;
import com.landgate.infrastructure.security.filter.JwtAuthFilter;
import com.landgate.infrastructure.security.filter.RequestBodyLimitFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security 配置 —— 对应 Go 版本的路由中间件链。
 * <p>
 * 三条独立的 SecurityFilterChain，分别处理不同类型的请求：
 * <ul>
 *   <li><b>publicFilterChain</b> — 公开端点（/api/v1/auth/login, /register），无需认证</li>
 *   <li><b>userFilterChain</b> — 用户端点（/api/v1/auth/me, /api-keys），JWT Bearer 认证</li>
 *   <li><b>gatewayFilterChain</b> — 网关端点（/v1/*, /v1beta/*, ...），API Key 认证</li>
 * </ul>
 * <p>
 * 所有 Filter Chain 均无状态（SessionCreationPolicy.STATELESS），禁用 CSRF。
 * 对应 Go 版本的三套中间件链：公开路由、JWT 路由、API Key 路由。
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final ApiKeyAuthFilter apiKeyAuthFilter;
    private final ClientRequestIdFilter clientRequestIdFilter;
    private final RequestBodyLimitFilter requestBodyLimitFilter;

    /**
     * 公开端点 Filter Chain：登录、注册、刷新令牌、登出。
     * 无需认证，所有请求直接放行。
     */
    @Bean
    public SecurityFilterChain publicFilterChain(HttpSecurity http) throws Exception {
        http.securityMatcher("/api/v1/auth/login", "/api/v1/auth/register",
                        "/api/v1/auth/refresh", "/api/v1/auth/logout",
                        "/api/v1/auth/verify-email", "/api/v1/auth/resend-verification-code",
                        "/api/v1/announcements")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }

    /**
     * 用户端点 Filter Chain：个人信息、会话吊销、API Key 管理。
     * 需要 JWT Bearer Token 认证。
     */
    @Bean
    public SecurityFilterChain userFilterChain(HttpSecurity http) throws Exception {
        http.securityMatcher("/api/v1/auth/me", "/api/v1/auth/revoke-all-sessions",
                        "/api/v1/auth/password", "/api/v1/auth/username",
                        "/api/v1/auth/api-keys/**", "/api/v1/user/**", "/api/v1/checkin/**", "/api/v1/balance/**", "/api/v1/admin/**",
                        "/api/v1/payment/**", "/api/v1/codes/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated());
        return http.build();
    }

    /**
     * API 网关端点 Filter Chain：AI API 代理调用。
     * 需要 API Key 认证（Header: x-api-key / Authorization: Bearer / x-goog-api-key）。
     * <p>
     * 路由覆盖：
     * <ul>
     *   <li>/v1/* — Anthropic/OpenAI 兼容协议</li>
     *   <li>/v1beta/* — Gemini 原生 API</li>
     *   <li>/chat/completions — OpenAI Chat Completions 别名</li>
     *   <li>/images/* — OpenAI Images 别名</li>
     *   <li>/responses — OpenAI Responses 别名</li>
     *   <li>/antigravity/* — Antigravity 专用路由</li>
     *   <li>/backend-api/codex/* — Codex CLI 直连路由</li>
     * </ul>
     */
    @Bean
    public SecurityFilterChain gatewayFilterChain(HttpSecurity http) throws Exception {
        http.securityMatcher("/v1/**", "/v1beta/**", "/chat/completions",
                        "/images/**", "/responses/**", "/antigravity/**",
                        "/backend-api/codex/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(requestBodyLimitFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(clientRequestIdFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(apiKeyAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated());
        return http.build();
    }

    /**
     * 密码编码器：bcrypt（cost = 10，Spring Security 默认）。
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * 禁用 JwtAuthFilter 的全局自动注册，避免拦截网关请求。
     * JwtAuthFilter 已在 userFilterChain 中显式添加，仅对用户端点生效。
     */
    @Bean
    public FilterRegistrationBean<JwtAuthFilter> jwtAuthFilterRegistration(JwtAuthFilter filter) {
        FilterRegistrationBean<JwtAuthFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    /**
     * 禁用 ApiKeyAuthFilter 的全局自动注册，避免重复执行。
     * ApiKeyAuthFilter 已在 gatewayFilterChain 中显式添加，仅对网关端点生效。
     */
    @Bean
    public FilterRegistrationBean<ApiKeyAuthFilter> apiKeyAuthFilterRegistration(ApiKeyAuthFilter filter) {
        FilterRegistrationBean<ApiKeyAuthFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
}
