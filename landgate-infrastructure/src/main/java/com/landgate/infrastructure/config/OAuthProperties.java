package com.landgate.infrastructure.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * OAuth 配置属性 —— 对应 application.yml 中 landgate.oauth 配置段。
 */
@Data
@Component
@ConfigurationProperties(prefix = "landgate.oauth")
public class OAuthProperties {

    /** 默认回调地址（前端未提供时使用） */
    private String defaultRedirectUri = "http://localhost:1455/auth/callback";

    /** OAuth state 临时存储有效期（秒） */
    private int stateExpireSeconds = 300;

    /** 各平台的 OAuth 提供商配置，key 为 platform name */
    private Map<String, ProviderConfig> providers = Map.of();

    @Data
    public static class ProviderConfig {
        /** OAuth 授权页面 URL（用户在此页面授权） */
        private String authorizeUrl;
        /** OAuth Token 端点 URL（code→token 交换、refresh_token→token 刷新） */
        private String tokenUrl;
        /** OAuth App 的 client_id */
        private String clientId;
        /** 请求的 OAuth 权限范围（如 "anthropic_creds"、"openid profile email offline_access"） */
        private String scopes;
        /** PKCE code_verifier 编码方式：base64url（Anthropic）或 hex（OpenAI），默认 base64url */
        private String pkceEncoding = "base64url";
        /** 授权 URL 额外的查询参数（如 OpenAI 的 codex_cli_simplified_flow=true） */
        private Map<String, String> extraAuthorizeParams = Map.of();
        /** Token 请求时使用的 User-Agent 头（如 OpenAI 需要 "codex-cli/0.91.0"） */
        private String userAgent;
        /** Token 刷新时使用的 scope（如与授权 scope 不同则单独指定），默认复用 scopes */
        private String refreshScopes;
        /** Token 请求体编码格式：form（application/x-www-form-urlencoded）或 json（application/json），默认 form */
        private String tokenExchangeFormat = "form";
        /** 平台默认回调地址（覆盖全局 defaultRedirectUri，如 Anthropic 使用官方回调页） */
        private String redirectUri;
        /** Device Code Flow: 设备码请求端点 URL（仅 OpenAI） */
        private String deviceCodeUrl;
        /** Device Code Flow: 设备码轮询端点 URL（仅 OpenAI） */
        private String devicePollUrl;
        /** Device Code Flow: 用户验证页面 URL（仅 OpenAI） */
        private String deviceVerificationUri;
        /** Device Code Flow: token 交换时的 redirect_uri（仅 OpenAI） */
        private String deviceRedirectUri;
    }
}
