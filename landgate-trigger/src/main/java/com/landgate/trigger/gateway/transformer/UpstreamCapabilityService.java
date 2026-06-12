package com.landgate.trigger.gateway.transformer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.landgate.domain.account.model.entity.AccountEntity;
import com.landgate.types.enums.AccountType;
import com.landgate.types.enums.Platform;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 上游能力探测服务 —— 判断 OpenAI APIKey 账号上游是否支持 /v1/responses 端点。
 * <p>
 * 探测结果存储在 accounts.extra JSON 中：
 * <ul>
 *   <li>{@code openai_responses_supported}: true | false（自动探测结果）</li>
 *   <li>{@code openai_responses_mode}: "auto" | "force_responses" | "force_chat_completions"（手动覆盖）</li>
 *   <li>{@code openai_passthrough}: true | false（passthrough 模式，跳过协议翻译）</li>
 * </ul>
 * <p>
 * 参照：sub2api {@code openai_compat/upstream_capability.go}
 */
@Slf4j
@Component
public class UpstreamCapabilityService {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String KEY_RESPONSES_SUPPORTED = "openai_responses_supported";
    private static final String KEY_RESPONSES_MODE = "openai_responses_mode";
    private static final String KEY_PASSTHROUGH = "openai_passthrough";
    private static final String KEY_OAUTH_PASSTHROUGH = "openai_oauth_passthrough";

    /**
     * 判定是否应使用 Responses API 端点。
     * <p>
     * 仅对 {@code platform=openai && type=apikey} 账号有意义。
     * OAuth 账号固定走 Codex 端点，不需要此判断。
     * <p>
     * 返回 false 的情况：
     * <ol>
     *   <li>手动覆盖为 force_chat_completions</li>
     *   <li>自动探测确认不支持（openai_responses_supported = false）</li>
     * </ol>
     * 未探测时默认返回 true（保持存量行为，与 Sub2API "现状即证据"原则一致）。
     *
     * @param account 账号实体
     * @return true 表示应使用 /v1/responses 端点
     */
    public boolean shouldUseResponsesAPI(AccountEntity account) {
        if (account == null) return true;

        // 仅对 OpenAI API Key 账号有意义
        if (account.getPlatform() != Platform.OPENAI || account.getType() != AccountType.API_KEY) {
            return true;
        }

        JsonNode extra = parseExtra(account);
        if (extra == null) return true;

        // 1. 检查手动覆盖模式
        if (extra.has(KEY_RESPONSES_MODE)) {
            String mode = extra.get(KEY_RESPONSES_MODE).asText();
            if ("force_chat_completions".equals(mode)) return false;
            if ("force_responses".equals(mode)) return true;
            // "auto": 继续检查探测结果
        }

        // 2. 检查自动探测结果
        if (extra.has(KEY_RESPONSES_SUPPORTED)) {
            JsonNode supported = extra.get(KEY_RESPONSES_SUPPORTED);
            if (supported.isBoolean() && !supported.asBoolean()) {
                return false;
            }
        }

        // 3. 默认 true（未探测时保持存量行为）
        return true;
    }

    /**
     * 判断账号是否启用了 passthrough 模式（跳过协议翻译）。
     * <p>
     * 兼容旧字段 {@code openai_oauth_passthrough}。
     */
    public boolean isPassthroughEnabled(AccountEntity account) {
        if (account == null) return false;
        JsonNode extra = parseExtra(account);
        if (extra == null) return false;

        if (extra.has(KEY_PASSTHROUGH) && extra.get(KEY_PASSTHROUGH).isBoolean()) {
            return extra.get(KEY_PASSTHROUGH).asBoolean();
        }
        // 兼容旧字段
        if (extra.has(KEY_OAUTH_PASSTHROUGH) && extra.get(KEY_OAUTH_PASSTHROUGH).isBoolean()) {
            return extra.get(KEY_OAUTH_PASSTHROUGH).asBoolean();
        }
        return false;
    }

    /**
     * 根据 HTTP 状态码判断上游是否支持 Responses 端点。
     * <p>
     * 仅 404 和 405 视为"不支持"，其他所有状态码（含 5xx）视为"支持"。
     * 用于运行时 404/405 自动回退（不持久化标记）。
     */
    public static boolean isResponsesEndpointSupportedByStatus(int statusCode) {
        return statusCode != 404 && statusCode != 405;
    }

    /** 安全解析账号 extra JSON */
    private JsonNode parseExtra(AccountEntity account) {
        try {
            if (account.getExtra() == null || account.getExtra().equals("{}")) return null;
            return JSON.readTree(account.getExtra());
        } catch (Exception e) {
            return null;
        }
    }
}
