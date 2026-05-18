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
    private String defaultRedirectUri = "http://localhost:80/oauth/callback";

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
        /** 请求的 OAuth 权限范围（如 "anthropic_creds"） */
        private String scopes;
    }
}
