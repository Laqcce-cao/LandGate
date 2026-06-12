package com.landgate.trigger.gateway;

import com.landgate.domain.account.model.entity.AccountEntity;
import com.landgate.trigger.gateway.transformer.UpstreamCapabilityService;
import com.landgate.types.enums.AccountType;
import com.landgate.types.enums.Platform;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * UpstreamCapabilityService 单元测试 —— 验证上游能力探测逻辑。
 * <p>
 * 参照：sub2api {@code upstream_capability_test.go}
 */
@DisplayName("UpstreamCapabilityService 上游能力探测测试")
class UpstreamCapabilityServiceTest {

    private final UpstreamCapabilityService service = new UpstreamCapabilityService();

    /**
     * 创建测试用的 AccountEntity。
     */
    private static AccountEntity createAccount(Platform platform, AccountType type, String extra) {
        AccountEntity account = new AccountEntity();
        account.setPlatform(platform);
        account.setType(type);
        account.setExtra(extra);
        return account;
    }

    // ========================
    // shouldUseResponsesAPI
    // ========================

    @Nested
    @DisplayName("shouldUseResponsesAPI")
    class ShouldUseResponsesAPI {

        @Test
        @DisplayName("null account → true（默认）")
        void nullAccountReturnsTrue() {
            assertTrue(service.shouldUseResponsesAPI(null));
        }

        @Test
        @DisplayName("非 OpenAI 平台 → true（不适用）")
        void nonOpenAIPlatformReturnsTrue() {
            AccountEntity account = createAccount(Platform.ANTHROPIC, AccountType.API_KEY, null);
            assertTrue(service.shouldUseResponsesAPI(account));
        }

        @Test
        @DisplayName("OAuth 类型 → true（不适用）")
        void oauthTypeReturnsTrue() {
            AccountEntity account = createAccount(Platform.OPENAI, AccountType.OAUTH, null);
            assertTrue(service.shouldUseResponsesAPI(account));
        }

        // 关键不变量：未探测 → true（保持存量行为，与 Sub2API "现状即证据"一致）

        @Test
        @DisplayName("未探测（extra=null）→ true")
        void unknownDefaultsToTrue() {
            AccountEntity account = createAccount(Platform.OPENAI, AccountType.API_KEY, null);
            assertTrue(service.shouldUseResponsesAPI(account));
        }

        @Test
        @DisplayName("未探测（extra={}）→ true")
        void unknownEmptyDefaultsToTrue() {
            AccountEntity account = createAccount(Platform.OPENAI, AccountType.API_KEY, "{}");
            assertTrue(service.shouldUseResponsesAPI(account));
        }

        @Test
        @DisplayName("未探测（key 缺失）→ true")
        void keyMissingDefaultsToTrue() {
            AccountEntity account = createAccount(Platform.OPENAI, AccountType.API_KEY,
                    "{\"other\":\"value\"}");
            assertTrue(service.shouldUseResponsesAPI(account));
        }

        @Test
        @DisplayName("未探测（wrong type string）→ true")
        void wrongTypeStringDefaultsToTrue() {
            AccountEntity account = createAccount(Platform.OPENAI, AccountType.API_KEY,
                    "{\"openai_responses_supported\":\"yes\"}");
            assertTrue(service.shouldUseResponsesAPI(account));
        }

        @Test
        @DisplayName("未探测（wrong type number）→ true")
        void wrongTypeNumberDefaultsToTrue() {
            AccountEntity account = createAccount(Platform.OPENAI, AccountType.API_KEY,
                    "{\"openai_responses_supported\":1}");
            assertTrue(service.shouldUseResponsesAPI(account));
        }

        // 已探测：标记决定

        @Test
        @DisplayName("明确支持（true）→ true")
        void explicitlySupported() {
            AccountEntity account = createAccount(Platform.OPENAI, AccountType.API_KEY,
                    "{\"openai_responses_supported\":true}");
            assertTrue(service.shouldUseResponsesAPI(account));
        }

        @Test
        @DisplayName("明确不支持（false）→ false")
        void explicitlyUnsupported() {
            AccountEntity account = createAccount(Platform.OPENAI, AccountType.API_KEY,
                    "{\"openai_responses_supported\":false}");
            assertFalse(service.shouldUseResponsesAPI(account));
        }

        // 手动覆盖模式

        @Test
        @DisplayName("force_chat_completions → false")
        void forceChatCompletions() {
            AccountEntity account = createAccount(Platform.OPENAI, AccountType.API_KEY,
                    "{\"openai_responses_mode\":\"force_chat_completions\"}");
            assertFalse(service.shouldUseResponsesAPI(account));
        }

        @Test
        @DisplayName("force_responses → true")
        void forceResponses() {
            AccountEntity account = createAccount(Platform.OPENAI, AccountType.API_KEY,
                    "{\"openai_responses_mode\":\"force_responses\"}");
            assertTrue(service.shouldUseResponsesAPI(account));
        }

        @Test
        @DisplayName("force_chat_completions 优先级高于探测结果")
        void forceOverridesDetection() {
            // 即使探测结果为 true，force_chat_completions 强制返回 false
            AccountEntity account = createAccount(Platform.OPENAI, AccountType.API_KEY,
                    "{\"openai_responses_supported\":true,\"openai_responses_mode\":\"force_chat_completions\"}");
            assertFalse(service.shouldUseResponsesAPI(account));
        }

        @Test
        @DisplayName("auto 模式 fallback 到探测结果")
        void autoModeFallsBackToDetection() {
            AccountEntity account = createAccount(Platform.OPENAI, AccountType.API_KEY,
                    "{\"openai_responses_supported\":false,\"openai_responses_mode\":\"auto\"}");
            assertFalse(service.shouldUseResponsesAPI(account));
        }

        @Test
        @DisplayName("解析异常的 extra → true（安全兜底）")
        void malformedExtra() {
            AccountEntity account = createAccount(Platform.OPENAI, AccountType.API_KEY,
                    "not valid json");
            assertTrue(service.shouldUseResponsesAPI(account));
        }
    }

    // ========================
    // isPassthroughEnabled
    // ========================

    @Nested
    @DisplayName("isPassthroughEnabled")
    class IsPassthroughEnabled {

        @Test
        @DisplayName("null account → false")
        void nullAccountReturnsFalse() {
            assertFalse(service.isPassthroughEnabled(null));
        }

        @Test
        @DisplayName("未配置 → false")
        void notConfiguredReturnsFalse() {
            AccountEntity account = createAccount(Platform.OPENAI, AccountType.API_KEY, null);
            assertFalse(service.isPassthroughEnabled(account));
        }

        @Test
        @DisplayName("openai_passthrough=true → true")
        void passthroughTrue() {
            AccountEntity account = createAccount(Platform.OPENAI, AccountType.API_KEY,
                    "{\"openai_passthrough\":true}");
            assertTrue(service.isPassthroughEnabled(account));
        }

        @Test
        @DisplayName("openai_passthrough=false → false")
        void passthroughFalse() {
            AccountEntity account = createAccount(Platform.OPENAI, AccountType.API_KEY,
                    "{\"openai_passthrough\":false}");
            assertFalse(service.isPassthroughEnabled(account));
        }

        @Test
        @DisplayName("旧字段 openai_oauth_passthrough=true → true（兼容）")
        void legacyOAuthPassthrough() {
            AccountEntity account = createAccount(Platform.OPENAI, AccountType.OAUTH,
                    "{\"openai_oauth_passthrough\":true}");
            assertTrue(service.isPassthroughEnabled(account));
        }

        @Test
        @DisplayName("新字段优先于旧字段")
        void newFieldTakesPrecedence() {
            AccountEntity account = createAccount(Platform.OPENAI, AccountType.API_KEY,
                    "{\"openai_passthrough\":false,\"openai_oauth_passthrough\":true}");
            assertFalse(service.isPassthroughEnabled(account));
        }
    }

    // ========================
    // isResponsesEndpointSupportedByStatus
    // ========================

    @Nested
    @DisplayName("isResponsesEndpointSupportedByStatus")
    class IsResponsesEndpointSupportedByStatus {

        @ParameterizedTest
        @DisplayName("404 和 405 返回 false")
        @ValueSource(ints = {404, 405})
        void unsupportedStatuses(int statusCode) {
            assertFalse(UpstreamCapabilityService.isResponsesEndpointSupportedByStatus(statusCode));
        }

        @ParameterizedTest
        @DisplayName("其他状态码返回 true")
        @ValueSource(ints = {200, 201, 400, 401, 403, 429, 500, 502, 503})
        void supportedStatuses(int statusCode) {
            assertTrue(UpstreamCapabilityService.isResponsesEndpointSupportedByStatus(statusCode));
        }
    }
}
