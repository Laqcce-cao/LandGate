package com.landgate.trigger.gateway.oauth;

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
    public static final String CLI_CURRENT_VERSION = "2.1.92";

    /** 指纹计算 salt */
    public static final String FINGERPRINT_SALT = "59cf53e54c78";

    /** CCH xxHash64 seed */
    public static final long CCH_SEED = 0x6E52736AC806831EL;

    /** Claude Code CLI UA 正则模式 */
    public static final Pattern CLAUDE_CLI_UA_PATTERN =
            Pattern.compile("claude-cli/\\d+\\.\\d+\\.\\d+", Pattern.CASE_INSENSITIVE);

    /** 已知的 Claude Code system prompt 模板（用于 Dice 系数检测，共 6 个） */
    public static final List<String> KNOWN_CC_PROMPTS = List.of(
            "You are Claude Code, Anthropic's official CLI for Claude.",
            "You are a Claude agent, built on Anthropic's Claude Agent SDK.",
            "You are Claude Code, Anthropic's official CLI for Claude, running within the Claude Agent SDK.",
            "You are a file search specialist for Claude Code, Anthropic's official CLI for Claude.",
            "You are a helpful AI assistant tasked with summarizing conversations.",
            "You are an interactive CLI tool that helps users"
    );

    /** Dice 系数阈值 */
    public static final double SYSTEM_PROMPT_THRESHOLD = 0.5;

    /** 完整伪装 beta tokens（非 haiku 模型） */
    public static final List<String> FULL_MIMICRY_BETAS = List.of(
            "claude-code-20250219",
            "oauth-2025-04-20",
            "interleaved-thinking-2025-05-14",
            "prompt-caching-scope-2026-01-05",
            "effort-2025-11-24",
            "context-management-2025-06-27",
            "extended-cache-ttl-2025-04-11"
    );

    /** 精简伪装 beta tokens（haiku 模型） */
    public static final List<String> HAIKU_MIMICRY_BETAS = List.of(
            "oauth-2025-04-20",
            "interleaved-thinking-2025-05-14"
    );

    /** Claude Code 伪装默认请求头 */
    public static final Map<String, String> DEFAULT_MIMICRY_HEADERS = Map.ofEntries(
            Map.entry("User-Agent", "claude-cli/2.1.92 (external, cli)"),
            Map.entry("X-Stainless-Lang", "js"),
            Map.entry("X-Stainless-Package-Version", "0.70.0"),
            Map.entry("X-Stainless-OS", "Linux"),
            Map.entry("X-Stainless-Arch", "arm64"),
            Map.entry("X-Stainless-Runtime", "node"),
            Map.entry("X-Stainless-Runtime-Version", "v24.13.0"),
            Map.entry("X-Stainless-Retry-Count", "0"),
            Map.entry("X-Stainless-Timeout", "600"),
            Map.entry("X-App", "cli"),
            Map.entry("Anthropic-Dangerous-Direct-Browser-Access", "true"),
            Map.entry("Accept", "application/json")
    );

    /** Claude Code system prompt 文本 */
    public static final String CLAUDE_CODE_SYSTEM_PROMPT =
            "You are Claude Code, Anthropic's official CLI for Claude.";

    /** Billing header 前缀标识 */
    public static final String BILLING_HEADER_PREFIX = "x-anthropic-billing-header: ";

    /** Claude Code system prompt 前缀（用于判断是否已是 CC prompt） */
    public static final String CLAUDE_CODE_PROMPT_PREFIX = "You are Claude Code";

    /** 默认 max_tokens */
    public static final int DEFAULT_MAX_TOKENS = 128000;

    /** 默认 temperature */
    public static final double DEFAULT_TEMPERATURE = 1.0;

    /** thinking budget_tokens 映射 */
    public static final Map<String, Integer> EFFORT_BUDGET_TOKENS = Map.of(
            "low", 1024,
            "medium", 4096,
            "high", 10240,
            "max", 32768
    );
}
