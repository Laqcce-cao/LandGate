package com.landgate.trigger.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.landgate.domain.account.model.entity.AccountEntity;
import com.landgate.domain.account.service.CredentialDomainService;
import com.landgate.types.enums.AccountType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 访问令牌提取服务 —— 从上游账号凭证中提取 Access Token。
 * <p>
 * 根据账号类型采用不同的提取策略：
 * <ul>
 *   <li>API_KEY / UPSTREAM — 直接从凭证中读取 api_key 字段</li>
 *   <li>OAUTH / SETUP_TOKEN — 读取 access_token 字段（长 Token 需 AES 解密）</li>
 *   <li>BEDROCK — 读取 access_key_id 字段</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GetAccessTokenService {

    private final CredentialDomainService credentialService;
    private static final ObjectMapper JSON = new ObjectMapper();

    public String getAccessToken(AccountEntity account) {
        String credentials = account.getCredentials();
        if (credentials == null || credentials.equals("{}")) {
            log.warn("Empty credentials for account: account_id={}", account.getId());
            return null;
        }

        try {
            var root = JSON.readTree(credentials);
            return switch (account.getType()) {
                case API_KEY, UPSTREAM -> {
                    var token = root.has("api_key") ? root.get("api_key").asText() : null;
                    yield token;
                }
                case OAUTH, SETUP_TOKEN -> {
                    // Check if token is about to expire (should have been proactively refreshed)
                    if (root.has("token_expires_at")) {
                        try {
                            Instant expiresAt = Instant.parse(root.get("token_expires_at").asText());
                            if (expiresAt.isBefore(Instant.now())) {
                                log.warn("OAuth token already expired for account: account_id={}, expires_at={}",
                                        account.getId(), expiresAt);
                            } else if (expiresAt.minusSeconds(60).isBefore(Instant.now())) {
                                log.warn("OAuth token expiring soon (<60s): account_id={}, expires_at={}",
                                        account.getId(), expiresAt);
                            }
                        } catch (Exception e) {
                            log.debug("Could not parse token_expires_at for account: account_id={}", account.getId());
                        }
                    }
                    var token = root.has("access_token") ? root.get("access_token").asText() : null;
                    if (token != null && token.length() > 100) {
                        try {
                            yield credentialService.decrypt(token);
                        } catch (Exception e) {
                            yield token;
                        }
                    }
                    yield token;
                }
                case BEDROCK -> root.has("access_key_id") ? root.get("access_key_id").asText() : null;
                default -> {
                    log.warn("Unknown account type: {}", account.getType());
                    yield null;
                }
            };
        } catch (Exception e) {
            log.error("Failed to parse credentials for account: account_id={}", account.getId(), e);
            return null;
        }
    }
}
