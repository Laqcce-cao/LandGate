package com.landgate.trigger.gateway.oauth;

import com.landgate.types.gateway.AnthropicApiProfile;
import com.landgate.types.gateway.AnthropicClaudeCodeProfile;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Claude Code 相关常量 —— CLI 版本号、伪装头、beta tokens、系统提示模板等。
 * <p>
 * 参照：sub2api {@code claude/constants.go}
 */
public final class ClaudeConstants {

    private ClaudeConstants() {}

    /** CLI 当前版本号（不可配置，必须与 DefaultHeaders 中的 UA 严格一致） */
    public static final String CLI_CURRENT_VERSION = AnthropicClaudeCodeProfile.CLI_CURRENT_VERSION;
    public static final String DEFAULT_CLAUDE_CLI_USER_AGENT =
            AnthropicClaudeCodeProfile.DEFAULT_CLAUDE_CLI_USER_AGENT;

    /** 指纹计算 salt */
    public static final String FINGERPRINT_SALT = AnthropicClaudeCodeProfile.FINGERPRINT_SALT;

    /** CCH xxHash64 seed */
    public static final long CCH_SEED = AnthropicClaudeCodeProfile.CCH_SEED;

    /** Claude Code CLI UA 正则模式 */
    public static final Pattern CLAUDE_CLI_UA_PATTERN =
            AnthropicClaudeCodeProfile.CLAUDE_CLI_UA_PATTERN;

    /** 已知的 Claude Code system prompt 模板（用于 Dice 系数检测，共 6 个） */
    public static final List<String> KNOWN_CC_PROMPTS = AnthropicClaudeCodeProfile.KNOWN_CC_PROMPTS;

    /** Dice 系数阈值 */
    public static final double SYSTEM_PROMPT_THRESHOLD = AnthropicClaudeCodeProfile.SYSTEM_PROMPT_THRESHOLD;

    /** 完整伪装 beta tokens（非 haiku 模型） */
    public static final List<String> FULL_MIMICRY_BETAS = AnthropicClaudeCodeProfile.FULL_MIMICRY_BETAS;

    /** 精简伪装 beta tokens（haiku 模型） */
    public static final List<String> HAIKU_MIMICRY_BETAS = AnthropicClaudeCodeProfile.HAIKU_MIMICRY_BETAS;

    /** Claude Code 伪装默认请求头 */
    public static final Map<String, String> DEFAULT_MIMICRY_HEADERS =
            AnthropicClaudeCodeProfile.DEFAULT_MIMICRY_HEADERS;

    /** Claude Code system prompt 文本 */
    public static final String CLAUDE_CODE_SYSTEM_PROMPT =
            AnthropicClaudeCodeProfile.CLAUDE_CODE_SYSTEM_PROMPT;

    /** Billing header 前缀标识 */
    public static final String BILLING_HEADER_PREFIX = AnthropicClaudeCodeProfile.BILLING_HEADER_PREFIX;

    /** Claude Code system prompt 前缀（用于判断是否已是 CC prompt） */
    public static final String CLAUDE_CODE_PROMPT_PREFIX = AnthropicClaudeCodeProfile.CLAUDE_CODE_PROMPT_PREFIX;

    /** 默认 max_tokens */
    public static final int DEFAULT_MAX_TOKENS = AnthropicClaudeCodeProfile.DEFAULT_MAX_TOKENS;

    /** 默认 temperature */
    public static final double DEFAULT_TEMPERATURE = AnthropicClaudeCodeProfile.DEFAULT_TEMPERATURE;

    /** 代理生成的 ephemeral cache_control 默认 TTL */
    public static final String DEFAULT_CACHE_CONTROL_TTL = AnthropicClaudeCodeProfile.DEFAULT_CACHE_CONTROL_TTL;

    /** 可选全局 1h cache_control 注入目标 TTL */
    public static final String CACHE_CONTROL_TTL_1H = AnthropicClaudeCodeProfile.CACHE_CONTROL_TTL_1H;

    /** thinking budget_tokens 映射 */
    public static final Map<String, Integer> EFFORT_BUDGET_TOKENS =
            AnthropicClaudeCodeProfile.EFFORT_BUDGET_TOKENS;

    public static final Map<String, String> MODEL_ALIASES = AnthropicClaudeCodeProfile.MODEL_ALIASES;

    public static String normalizeModelId(String model) {
        return AnthropicClaudeCodeProfile.normalizeModelId(model);
    }

    public static String mergeBetaHeader(List<String> requiredBetas, String incomingBetaHeader) {
        return AnthropicClaudeCodeProfile.mergeBetaHeader(requiredBetas, incomingBetaHeader);
    }

    public static List<String> requiredMimicryBetas(String model) {
        return AnthropicClaudeCodeProfile.requiredMimicryBetas(model);
    }

    public static String ensureOAuthBetaHeader(String model, String clientBetaHeader) {
        return AnthropicClaudeCodeProfile.ensureOAuthBetaHeader(model, clientBetaHeader);
    }
}
