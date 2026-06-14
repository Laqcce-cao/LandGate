package com.landgate.trigger.gateway.oauth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.landgate.types.gateway.AnthropicApiProfile;
import com.landgate.types.gateway.AnthropicClaudeCodeProfile;
import com.landgate.types.gateway.AnthropicMessagesBodyPolicy;
import com.landgate.types.gateway.GatewayHeaderPolicy;
import com.landgate.types.gateway.MetadataUserIdParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Claude Code 客户端检测器 —— 分端点差异化校验。
 * <p>
 * /v1/messages 端点：完整 7 步校验（UA + system prompt Dice 系数 + 必要 header + metadata.user_id）<br>
 * 非 /v1/messages 端点：仅 UA 匹配
 * <p>
 * 参照：sub2api {@code claude_code_validator.go} + {@code gateway_helper.go}
 */
@Slf4j
@Component
public class ClaudeCodeDetector {

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * 完整校验（仅 /v1/messages 端点）。
     *
     * @param userAgent      请求 UA 头
     * @param metadataUserId 解析后的 metadata.user_id（可为 null）
     * @param systemPrompt   系统提示文本（从 body 提取）
     * @param maxTokens      请求中的 max_tokens
     * @param model          请求模型名
     * @param headers        请求头全集
     * @return true 如果是 Claude Code 客户端
     */
    public boolean validateForMessages(String userAgent, String metadataUserId,
                                        String systemPrompt, int maxTokens, String model,
                                        Map<String, String> headers) {
        // Step 1: UA 必须匹配 claude-cli/X.Y.Z
        if (userAgent == null || !AnthropicClaudeCodeProfile.CLAUDE_CLI_UA_PATTERN.matcher(userAgent).matches()) {
            return false;
        }

        // Step 2: 探针绕过 —— max_tokens=1 + haiku 模型 → 跳过 system prompt 检查
        boolean isProbe = maxTokens == 1 && model != null && model.toLowerCase().contains("haiku");

        // Step 3: System prompt Dice 系数检测
        if (!isProbe && systemPrompt != null && !systemPrompt.isEmpty()) {
            if (!matchesKnownCCPrompt(systemPrompt)) {
                return false;
            }
        }

        // Step 4-6: 必要 header 检查
        if (!hasRequiredCCHeaders(headers)) {
            return false;
        }

        // Step 7: metadata.user_id 可解析
        if (metadataUserId != null && !metadataUserId.isEmpty()) {
            if (MetadataUserIdParser.parse(metadataUserId) == null) {
                return false;
            }
        }

        return true;
    }

    /**
     * 简化校验（非 /v1/messages 端点，无 system prompt 可检查）。
     * UA 匹配 claude-cli/* 即视为 CC 客户端。
     */
    public boolean validateForNonMessages(String userAgent) {
        return userAgent != null
                && AnthropicClaudeCodeProfile.CLAUDE_CLI_UA_PATTERN.matcher(userAgent).matches();
    }

    /**
     * 从请求 body 中提取 system prompt 文本。
     */
    public static String extractSystemPrompt(String body) {
        try {
            JsonNode root = JSON.readTree(body);
            if (root.has(AnthropicMessagesBodyPolicy.FIELD_SYSTEM)) {
                JsonNode system = root.get(AnthropicMessagesBodyPolicy.FIELD_SYSTEM);
                if (system.isTextual()) return system.asText();
                if (system.isArray()) {
                    StringBuilder sb = new StringBuilder();
                    for (JsonNode block : system) {
                        if (AnthropicMessagesBodyPolicy.TYPE_TEXT.equals(
                                block.has(AnthropicMessagesBodyPolicy.FIELD_TYPE)
                                        ? block.get(AnthropicMessagesBodyPolicy.FIELD_TYPE).asText()
                                        : "")
                                && block.has(AnthropicMessagesBodyPolicy.FIELD_TEXT)) {
                            if (sb.length() > 0) sb.append("\n");
                            sb.append(block.get(AnthropicMessagesBodyPolicy.FIELD_TEXT).asText());
                        }
                    }
                    return sb.toString();
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return null;
    }

    /**
     * 检查 system prompt 文本是否匹配已知的 CC 模板（Dice 系数 >= 0.5）。
     */
    private static boolean matchesKnownCCPrompt(String candidate) {
        for (String template : AnthropicClaudeCodeProfile.KNOWN_CC_PROMPTS) {
            double sim = diceCoefficient(candidate, template);
            if (sim >= AnthropicClaudeCodeProfile.SYSTEM_PROMPT_THRESHOLD) {
                return true;
            }
        }
        return false;
    }

    /**
     * 计算两个字符串的 Dice 系数。
     */
    private static double diceCoefficient(String a, String b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return 0.0;
        Set<String> bigramsA = bigrams(a);
        Set<String> bigramsB = bigrams(b);
        Set<String> intersection = new HashSet<>(bigramsA);
        intersection.retainAll(bigramsB);
        return (2.0 * intersection.size()) / (bigramsA.size() + bigramsB.size());
    }

    private static Set<String> bigrams(String s) {
        Set<String> result = new HashSet<>();
        for (int i = 0; i < s.length() - 1; i++) {
            result.add(s.substring(i, i + 2).toLowerCase());
        }
        return result;
    }

    /**
     * 检查是否存在必要的 CC 请求头。
     */
    private static boolean hasRequiredCCHeaders(Map<String, String> headers) {
        if (headers == null) return false;
        String xApp = GatewayHeaderPolicy.value(headers, AnthropicApiProfile.HEADER_X_APP);
        String beta = GatewayHeaderPolicy.value(headers, AnthropicApiProfile.HEADER_ANTHROPIC_BETA);
        String version = GatewayHeaderPolicy.value(headers, AnthropicApiProfile.HEADER_ANTHROPIC_VERSION);
        return xApp != null && !xApp.isEmpty()
                && beta != null && !beta.isEmpty()
                && version != null && !version.isEmpty();
    }
}
