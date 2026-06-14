package com.landgate.types.gateway;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Stable Claude Code compatibility profile facts for Anthropic OAuth mimicry.
 *
 * <p>This type owns Claude Code protocol/header/beta/default facts only. It
 * must not build HTTP requests, detect clients, rewrite request bodies, compute
 * billing signatures, or select accounts.</p>
 */
public final class AnthropicClaudeCodeProfile {

    public static final String BETA_CLAUDE_CODE = "claude-code-20250219";
    public static final String BETA_OAUTH = AnthropicApiProfile.BETA_OAUTH;
    public static final String BETA_INTERLEAVED_THINKING = "interleaved-thinking-2025-05-14";
    public static final String BETA_FINE_GRAINED_TOOL_STREAMING = "fine-grained-tool-streaming-2025-05-14";
    public static final String BETA_TOKEN_COUNTING = "token-counting-2024-11-01";
    public static final String BETA_PROMPT_CACHING_SCOPE = "prompt-caching-scope-2026-01-05";
    public static final String BETA_EFFORT = "effort-2025-11-24";
    public static final String BETA_CONTEXT_MANAGEMENT = "context-management-2025-06-27";
    public static final String BETA_EXTENDED_CACHE_TTL = "extended-cache-ttl-2025-04-11";

    public static final List<String> FULL_MIMICRY_BETAS = List.of(
            BETA_CLAUDE_CODE,
            BETA_OAUTH,
            BETA_INTERLEAVED_THINKING,
            BETA_PROMPT_CACHING_SCOPE,
            BETA_EFFORT,
            BETA_CONTEXT_MANAGEMENT,
            BETA_EXTENDED_CACHE_TTL);

    public static final List<String> HAIKU_MIMICRY_BETAS = List.of(
            BETA_OAUTH,
            BETA_INTERLEAVED_THINKING);

    public static final List<String> COUNT_TOKENS_BETAS = List.of(
            BETA_CLAUDE_CODE,
            BETA_OAUTH,
            BETA_INTERLEAVED_THINKING,
            BETA_TOKEN_COUNTING);

    public static final String CLI_CURRENT_VERSION = "2.1.92";
    public static final String DEFAULT_CLAUDE_CLI_USER_AGENT =
            "claude-cli/" + CLI_CURRENT_VERSION + " (external, cli)";
    public static final Pattern CLAUDE_CLI_UA_PATTERN =
            Pattern.compile("claude-cli/\\d+\\.\\d+\\.\\d+", Pattern.CASE_INSENSITIVE);

    public static final String FINGERPRINT_SALT = "59cf53e54c78";
    public static final long CCH_SEED = 0x6E52736AC806831EL;

    public static final String HEADER_X_STAINLESS_LANG = "X-Stainless-Lang";
    public static final String HEADER_X_STAINLESS_PACKAGE_VERSION = "X-Stainless-Package-Version";
    public static final String HEADER_X_STAINLESS_OS = "X-Stainless-OS";
    public static final String HEADER_X_STAINLESS_ARCH = "X-Stainless-Arch";
    public static final String HEADER_X_STAINLESS_RUNTIME = "X-Stainless-Runtime";
    public static final String HEADER_X_STAINLESS_RUNTIME_VERSION = "X-Stainless-Runtime-Version";
    public static final String HEADER_X_STAINLESS_RETRY_COUNT = "X-Stainless-Retry-Count";
    public static final String HEADER_X_STAINLESS_TIMEOUT = "X-Stainless-Timeout";
    public static final String HEADER_ANTHROPIC_DANGEROUS_DIRECT_BROWSER_ACCESS =
            "Anthropic-Dangerous-Direct-Browser-Access";

    public static final Map<String, String> DEFAULT_MIMICRY_HEADERS = Map.ofEntries(
            Map.entry(AnthropicApiProfile.HEADER_USER_AGENT, DEFAULT_CLAUDE_CLI_USER_AGENT),
            Map.entry(HEADER_X_STAINLESS_LANG, "js"),
            Map.entry(HEADER_X_STAINLESS_PACKAGE_VERSION, "0.70.0"),
            Map.entry(HEADER_X_STAINLESS_OS, "Linux"),
            Map.entry(HEADER_X_STAINLESS_ARCH, "arm64"),
            Map.entry(HEADER_X_STAINLESS_RUNTIME, "node"),
            Map.entry(HEADER_X_STAINLESS_RUNTIME_VERSION, "v24.13.0"),
            Map.entry(HEADER_X_STAINLESS_RETRY_COUNT, "0"),
            Map.entry(HEADER_X_STAINLESS_TIMEOUT, "600"),
            Map.entry(AnthropicApiProfile.HEADER_X_APP, AnthropicApiProfile.X_APP_CLI),
            Map.entry(HEADER_ANTHROPIC_DANGEROUS_DIRECT_BROWSER_ACCESS, "true"),
            Map.entry(AnthropicApiProfile.HEADER_ACCEPT, AnthropicApiProfile.MEDIA_TYPE_JSON));

    public static final List<String> KNOWN_CC_PROMPTS = List.of(
            "You are Claude Code, Anthropic's official CLI for Claude.",
            "You are a Claude agent, built on Anthropic's Claude Agent SDK.",
            "You are Claude Code, Anthropic's official CLI for Claude, running within the Claude Agent SDK.",
            "You are a file search specialist for Claude Code, Anthropic's official CLI for Claude.",
            "You are a helpful AI assistant tasked with summarizing conversations.",
            "You are an interactive CLI tool that helps users");

    public static final String CLAUDE_CODE_SYSTEM_PROMPT =
            "You are Claude Code, Anthropic's official CLI for Claude.";
    public static final String BILLING_HEADER_PREFIX = "x-anthropic-billing-header: ";
    public static final String CLAUDE_CODE_PROMPT_PREFIX = "You are Claude Code";

    public static final double SYSTEM_PROMPT_THRESHOLD = 0.5;
    public static final int DEFAULT_MAX_TOKENS = 128000;
    public static final double DEFAULT_TEMPERATURE = 1.0;
    public static final String DEFAULT_CACHE_CONTROL_TTL = GatewayCacheTtlPolicy.TARGET_5M;
    public static final String CACHE_CONTROL_TTL_1H = GatewayCacheTtlPolicy.TARGET_1H;

    public static final String PROPERTY_REWRITE_MESSAGE_CACHE_CONTROL =
            "landgate.gateway.anthropic.rewrite-message-cache-control";
    public static final String PROPERTY_CACHE_TTL_1H_INJECTION =
            "landgate.gateway.anthropic.cache-ttl-1h-injection";

    public static final Map<String, Integer> EFFORT_BUDGET_TOKENS = Map.of(
            "low", 1024,
            "medium", 4096,
            "high", 10240,
            "max", 32768);

    public static final Map<String, String> MODEL_ALIASES = Map.of(
            "claude-sonnet-4-5", "claude-sonnet-4-5-20250929",
            "claude-opus-4-5", "claude-opus-4-5-20251101",
            "claude-haiku-4-5", "claude-haiku-4-5-20251001");

    private AnthropicClaudeCodeProfile() {
    }

    public static String fullMimicryBetaHeader() {
        return String.join(",", FULL_MIMICRY_BETAS);
    }

    public static String haikuMimicryBetaHeader() {
        return String.join(",", HAIKU_MIMICRY_BETAS);
    }

    public static String countTokensBetaHeader() {
        return String.join(",", COUNT_TOKENS_BETAS);
    }

    public static List<String> fullMimicryCountTokensBetas() {
        ArrayList<String> betas = new ArrayList<>(FULL_MIMICRY_BETAS);
        if (!betas.contains(BETA_TOKEN_COUNTING)) {
            betas.add(BETA_TOKEN_COUNTING);
        }
        return List.copyOf(betas);
    }

    public static String ensureCountTokensOAuthBetaHeader(String model, String clientBetaHeader) {
        return ensureCountTokensOAuthBetaHeader(model, clientBetaHeader, Set.of());
    }

    public static String ensureCountTokensOAuthBetaHeader(String model, String clientBetaHeader,
                                                         Set<String> droppedBetas) {
        String beta = clientBetaHeader == null || clientBetaHeader.isBlank()
                ? countTokensBetaHeader()
                : ensureOAuthBetaHeader(model, clientBetaHeader);
        if (containsBetaToken(beta, BETA_TOKEN_COUNTING)) {
            return stripBetaTokens(beta, droppedBetas);
        }
        return stripBetaTokens(beta + "," + BETA_TOKEN_COUNTING, droppedBetas);
    }

    public static String normalizeModelId(String model) {
        if (model == null) {
            return null;
        }
        return MODEL_ALIASES.getOrDefault(model, model);
    }

    public static List<String> requiredMimicryBetas(String model) {
        return model != null && model.toLowerCase().contains("haiku")
                ? HAIKU_MIMICRY_BETAS
                : FULL_MIMICRY_BETAS;
    }

    public static String mergeBetaHeader(List<String> requiredBetas, String incomingBetaHeader) {
        return mergeBetaHeader(requiredBetas, incomingBetaHeader, Set.of());
    }

    public static String mergeBetaHeader(List<String> requiredBetas, String incomingBetaHeader,
                                         Set<String> droppedBetas) {
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        addBetaTokens(tokens, requiredBetas, droppedBetas);
        addBetaTokens(tokens, incomingBetaHeader, droppedBetas);
        return String.join(",", tokens);
    }

    public static String ensureOAuthBetaHeader(String model, String clientBetaHeader) {
        String incoming = clientBetaHeader == null ? "" : clientBetaHeader.trim();
        if (!incoming.isEmpty()) {
            if (containsBetaToken(incoming, BETA_OAUTH)) {
                return incoming;
            }
            List<String> parts = new ArrayList<>(splitBetaHeader(incoming));
            int claudeCodeIndex = parts.indexOf(BETA_CLAUDE_CODE);
            if (claudeCodeIndex >= 0) {
                parts.add(claudeCodeIndex + 1, BETA_OAUTH);
                return String.join(",", parts);
            }
            return BETA_OAUTH + "," + incoming;
        }
        return model != null && model.toLowerCase().contains("haiku")
                ? haikuMimicryBetaHeader()
                : BETA_CLAUDE_CODE + "," + BETA_OAUTH + "," + BETA_INTERLEAVED_THINKING + ","
                + BETA_FINE_GRAINED_TOOL_STREAMING;
    }

    public static boolean containsBetaToken(String header, String token) {
        if (header == null || header.isBlank() || token == null || token.isBlank()) {
            return false;
        }
        return splitBetaHeader(header).contains(token.trim());
    }

    public static String stripBetaTokens(String header, Set<String> droppedBetas) {
        if (header == null || header.isBlank()) {
            return "";
        }
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        addBetaTokens(tokens, header, droppedBetas);
        return String.join(",", tokens);
    }

    private static void addBetaTokens(LinkedHashSet<String> tokens, List<String> values, Set<String> droppedBetas) {
        if (values == null) {
            return;
        }
        for (String value : values) {
            addBetaToken(tokens, value, droppedBetas);
        }
    }

    private static void addBetaTokens(LinkedHashSet<String> tokens, String header, Set<String> droppedBetas) {
        for (String value : splitBetaHeader(header)) {
            addBetaToken(tokens, value, droppedBetas);
        }
    }

    private static void addBetaToken(LinkedHashSet<String> tokens, String value, Set<String> droppedBetas) {
        String token = value == null ? "" : value.trim();
        if (token.isEmpty() || (droppedBetas != null && droppedBetas.contains(token))) {
            return;
        }
        tokens.add(token);
    }

    private static List<String> splitBetaHeader(String header) {
        if (header == null || header.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(header.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList();
    }
}
