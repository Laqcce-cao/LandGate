package com.landgate.trigger.gateway.oauth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.landgate.domain.account.model.entity.AccountEntity;
import com.landgate.trigger.gateway.oauth.FingerprintService.ClientFingerprint;
import com.landgate.types.gateway.AnthropicMessagesBodyPolicy;
import com.landgate.types.gateway.AnthropicOAuthToolNamePolicy;
import com.landgate.types.gateway.AnthropicApiProfile;
import com.landgate.types.gateway.AnthropicClaudeCodeProfile;
import com.landgate.types.gateway.AnthropicThinkingPolicy;
import com.landgate.types.gateway.GatewayHeaderPolicy;
import com.landgate.types.gateway.MetadataUserIdParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.http.HttpRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * OAuth 伪装服务 —— Claude Code 客户端伪装的核心实现。
 * <p>
 * 三个核心操作：
 * <ul>
 *   <li>{@link #rewriteSystemForNonClaudeCode} — 替换 system 为 billing block + CC prompt</li>
 *   <li>{@link #normalizeClaudeOAuthRequestBody} — 标准化 body（模型名、tools、temperature 等）</li>
 *   <li>{@link #applyClaudeCodeMimicHeaders} — 强制设置 Claude Code 伪装请求头</li>
 * </ul>
 * <p>
 * 参照：sub2api {@code gateway_service.go} 对应函数。
 */
@Slf4j
@Component
public class OAuthMimicryService {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Value("${" + AnthropicClaudeCodeProfile.PROPERTY_REWRITE_MESSAGE_CACHE_CONTROL + ":false}")
    private boolean rewriteMessageCacheControl;

    @Value("${" + AnthropicClaudeCodeProfile.PROPERTY_CACHE_TTL_1H_INJECTION + ":false}")
    private boolean cacheTTL1hInjection;

    public record ClaudeOAuthBodyMimicryResult(String body, AnthropicToolNameRewrite toolNameRewrite) {
    }

    /**
     * 重写 system prompt —— 将非 Claude Code 客户端的 system 替换为
     * [billing attribution block, Claude Code prompt]，原始 system 注入到 messages 开头。
     * <p>
     * 仅对非 haiku 模型执行（haiku 模型不需要此伪装）。
     *
     * @param body  Anthropic Messages 格式的请求 body
     * @param model 模型名
     * @return 改写后的 body
     */
    public String rewriteSystemForNonClaudeCode(String body, String model) {
        try {
            JsonNode root = JSON.readTree(body);
            if (!root.isObject()) return body;

            // 1. 提取原始 system 文本
            String originalSystemText = extractSystemText(root);

            // 2. 构造 billing block + CC prompt 两段 system 数组
            BillingHeaderService billingService = new BillingHeaderService();
            String billingBlockJson = billingService.buildBillingAttributionBlockJSON(
                    body, ClaudeConstants.CLI_CURRENT_VERSION);
            if (billingBlockJson == null) return body;

            JsonNode billingBlock = JSON.readTree(billingBlockJson);
            ObjectNode ccPromptBlock = JSON.createObjectNode();
            ccPromptBlock.put(AnthropicMessagesBodyPolicy.FIELD_TYPE, AnthropicMessagesBodyPolicy.TYPE_TEXT);
            ccPromptBlock.put(AnthropicMessagesBodyPolicy.FIELD_TEXT, ClaudeConstants.CLAUDE_CODE_SYSTEM_PROMPT);
            ObjectNode cacheControl = JSON.createObjectNode();
            cacheControl.put(AnthropicMessagesBodyPolicy.FIELD_TYPE,
                    AnthropicMessagesBodyPolicy.CACHE_CONTROL_TYPE_EPHEMERAL);
            ccPromptBlock.set(AnthropicMessagesBodyPolicy.FIELD_CACHE_CONTROL, cacheControl);

            ArrayNode newSystem = JSON.createArrayNode();
            newSystem.add(billingBlock);
            newSystem.add(ccPromptBlock);

            ObjectNode rootObj = (ObjectNode) root;
            rootObj.set(AnthropicMessagesBodyPolicy.FIELD_SYSTEM, newSystem);

            // 3. 将原始 system prompt 作为 user/assistant 消息对注入到 messages 开头
            String ccPromptTrimmed = ClaudeConstants.CLAUDE_CODE_SYSTEM_PROMPT.trim();
            if (originalSystemText != null && !originalSystemText.isEmpty()
                    && !originalSystemText.equals(ccPromptTrimmed)
                    && !originalSystemText.startsWith(ClaudeConstants.CLAUDE_CODE_PROMPT_PREFIX)) {

                // 构造 instruction 消息
                ObjectNode instrMsg = JSON.createObjectNode();
                instrMsg.put(AnthropicMessagesBodyPolicy.FIELD_ROLE, AnthropicMessagesBodyPolicy.ROLE_USER);
                ArrayNode instrContent = JSON.createArrayNode();
                ObjectNode instrText = JSON.createObjectNode();
                instrText.put(AnthropicMessagesBodyPolicy.FIELD_TYPE, AnthropicMessagesBodyPolicy.TYPE_TEXT);
                instrText.put(AnthropicMessagesBodyPolicy.FIELD_TEXT, "[System Instructions]\n" + originalSystemText);
                instrContent.add(instrText);
                instrMsg.set(AnthropicMessagesBodyPolicy.FIELD_CONTENT, instrContent);

                // 构造 acknowledgment 消息
                ObjectNode ackMsg = JSON.createObjectNode();
                ackMsg.put(AnthropicMessagesBodyPolicy.FIELD_ROLE, AnthropicMessagesBodyPolicy.ROLE_ASSISTANT);
                ArrayNode ackContent = JSON.createArrayNode();
                ObjectNode ackText = JSON.createObjectNode();
                ackText.put(AnthropicMessagesBodyPolicy.FIELD_TYPE, AnthropicMessagesBodyPolicy.TYPE_TEXT);
                ackText.put(AnthropicMessagesBodyPolicy.FIELD_TEXT, "Understood. I will follow these instructions.");
                ackContent.add(ackText);
                ackMsg.set(AnthropicMessagesBodyPolicy.FIELD_CONTENT, ackContent);

                // 重建 messages 数组
                ArrayNode newMessages = JSON.createArrayNode();
                newMessages.add(instrMsg);
                newMessages.add(ackMsg);
                if (rootObj.has(AnthropicMessagesBodyPolicy.FIELD_MESSAGES)
                        && rootObj.get(AnthropicMessagesBodyPolicy.FIELD_MESSAGES).isArray()) {
                    for (JsonNode msg : rootObj.get(AnthropicMessagesBodyPolicy.FIELD_MESSAGES)) {
                        newMessages.add(msg);
                    }
                }
                rootObj.set(AnthropicMessagesBodyPolicy.FIELD_MESSAGES, newMessages);
            }

            return JSON.writeValueAsString(rootObj);
        } catch (Exception e) {
            log.warn("Failed to rewrite system for non-ClaudeCode: {}", e.getMessage());
            return body;
        }
    }

    /**
     * 标准化 OAuth 请求 body，对齐真实 Claude Code CLI 的字段行为。
     * <p>
     * 操作：标准化 system（替换 OpenCode 文本 + 剥离 cache_control）→
     * 映射短模型名 → 确保 tools 字段存在 → 注入 metadata.user_id →
     * 确保 temperature（默认 1）→ 确保 max_tokens（默认 128000）→
     * 注入 context_management → 清理无 tools 时的 tool_choice。
     *
     * @param body             Anthropic Messages 格式的请求 body
     * @param model            模型名
     * @param injectMetadata   是否注入 metadata.user_id
     * @param metadataUserId   metadata.user_id 值（injectMetadata=true 时使用）
     * @return 标准化后的 body
     */
    public String normalizeClaudeOAuthRequestBody(String body, String model,
                                                   boolean injectMetadata, String metadataUserId) {
        return normalizeClaudeOAuthRequestBody(body, model, injectMetadata, metadataUserId, true);
    }

    public String normalizeClaudeOAuthRequestBody(String body, String model,
                                                   boolean injectMetadata, String metadataUserId,
                                                   boolean stripSystemCacheControl) {
        if (body == null || body.isEmpty()) return body;

        try {
            JsonNode root = JSON.readTree(body);
            if (!root.isObject()) return body;
            ObjectNode rootObj = (ObjectNode) root;
            boolean modified = false;

            // 标准化 system（替换 OpenCode 文本；按调用链语义决定是否剥离 cache_control）
            if (rootObj.has(AnthropicMessagesBodyPolicy.FIELD_SYSTEM)) {
                JsonNode system = rootObj.get(AnthropicMessagesBodyPolicy.FIELD_SYSTEM);
                if (system.isTextual()) {
                    String sanitized = sanitizeSystemText(system.asText());
                    if (!sanitized.equals(system.asText())) {
                        rootObj.put(AnthropicMessagesBodyPolicy.FIELD_SYSTEM, sanitized);
                        modified = true;
                    }
                } else if (system.isArray()) {
                    ArrayNode sysArray = (ArrayNode) system;
                    for (int i = 0; i < sysArray.size(); i++) {
                        JsonNode item = sysArray.get(i);
                        if (item.isObject() && item.has(AnthropicMessagesBodyPolicy.FIELD_TEXT)) {
                            String text = item.get(AnthropicMessagesBodyPolicy.FIELD_TEXT).asText();
                            String sanitized = sanitizeSystemText(text);
                            if (!sanitized.equals(text)) {
                                ((ObjectNode) item).put(AnthropicMessagesBodyPolicy.FIELD_TEXT, sanitized);
                                modified = true;
                            }
                        }
                        if (stripSystemCacheControl && AnthropicMessagesBodyPolicy.hasCacheControl(item)) {
                            ((ObjectNode) item).remove(AnthropicMessagesBodyPolicy.FIELD_CACHE_CONTROL);
                            modified = true;
                        }
                    }
                }
            }

            // 标准化 model ID（短名→全名映射）
            if (rootObj.has(AnthropicMessagesBodyPolicy.FIELD_MODEL)) {
                String rawModel = rootObj.get(AnthropicMessagesBodyPolicy.FIELD_MODEL).asText();
                String normalized = ClaudeConstants.normalizeModelId(rawModel);
                if (!normalized.equals(rawModel)) {
                    rootObj.put(AnthropicMessagesBodyPolicy.FIELD_MODEL, normalized);
                    modified = true;
                }
            }

            // 确保 tools 字段存在
            if (!rootObj.has(AnthropicMessagesBodyPolicy.FIELD_TOOLS)) {
                rootObj.set(AnthropicMessagesBodyPolicy.FIELD_TOOLS, JSON.createArrayNode());
                modified = true;
            }

            // 注入 metadata.user_id
            if (injectMetadata && metadataUserId != null && !metadataUserId.isEmpty()) {
                if (rootObj.has(AnthropicMessagesBodyPolicy.FIELD_METADATA)
                        && rootObj.get(AnthropicMessagesBodyPolicy.FIELD_METADATA).isObject()) {
                    ObjectNode metadata = (ObjectNode) rootObj.get(AnthropicMessagesBodyPolicy.FIELD_METADATA);
                    if (!metadata.has(AnthropicMessagesBodyPolicy.FIELD_USER_ID)
                            || metadata.get(AnthropicMessagesBodyPolicy.FIELD_USER_ID).asText().isEmpty()) {
                        metadata.put(AnthropicMessagesBodyPolicy.FIELD_USER_ID, metadataUserId);
                        modified = true;
                    }
                } else {
                    ObjectNode metadata = JSON.createObjectNode();
                    metadata.put(AnthropicMessagesBodyPolicy.FIELD_USER_ID, metadataUserId);
                    rootObj.set(AnthropicMessagesBodyPolicy.FIELD_METADATA, metadata);
                    modified = true;
                }
            }

            // 确保 temperature（默认 1）
            if (!rootObj.has(AnthropicMessagesBodyPolicy.FIELD_TEMPERATURE)) {
                rootObj.put(AnthropicMessagesBodyPolicy.FIELD_TEMPERATURE, ClaudeConstants.DEFAULT_TEMPERATURE);
                modified = true;
            }

            // 确保 max_tokens（默认 128000）
            if (!rootObj.has(AnthropicMessagesBodyPolicy.FIELD_MAX_TOKENS)) {
                rootObj.put(AnthropicMessagesBodyPolicy.FIELD_MAX_TOKENS, ClaudeConstants.DEFAULT_MAX_TOKENS);
                modified = true;
            }

            // context_management：thinking 启用时自动补齐
            if (!rootObj.has(AnthropicThinkingPolicy.FIELD_CONTEXT_MANAGEMENT)
                    && AnthropicThinkingPolicy.shouldInjectContextManagement(
                    rootObj.get(AnthropicThinkingPolicy.FIELD_THINKING))) {
                ObjectNode cm = JSON.createObjectNode();
                ArrayNode edits = JSON.createArrayNode();
                ObjectNode edit = JSON.createObjectNode();
                edit.put(AnthropicMessagesBodyPolicy.FIELD_TYPE,
                        AnthropicThinkingPolicy.CONTEXT_MANAGEMENT_CLEAR_THINKING_EDIT);
                edit.put(AnthropicThinkingPolicy.FIELD_KEEP,
                        AnthropicThinkingPolicy.CONTEXT_MANAGEMENT_KEEP_ALL);
                edits.add(edit);
                cm.set(AnthropicThinkingPolicy.FIELD_EDITS, edits);
                rootObj.set(AnthropicThinkingPolicy.FIELD_CONTEXT_MANAGEMENT, cm);
                modified = true;
            }

            // 清理 tool_choice（无 tools 或空 tools 时删除）
            JsonNode tools = rootObj.get(AnthropicMessagesBodyPolicy.FIELD_TOOLS);
            if ((tools == null || !tools.isArray() || tools.size() == 0)
                    && rootObj.has(AnthropicMessagesBodyPolicy.FIELD_TOOL_CHOICE)) {
                rootObj.remove(AnthropicMessagesBodyPolicy.FIELD_TOOL_CHOICE);
                modified = true;
            }

            return modified ? JSON.writeValueAsString(rootObj) : body;
        } catch (Exception e) {
            log.warn("Failed to normalize OAuth request body: {}", e.getMessage());
            return body;
        }
    }

    /**
     * 简化版 normalizeClaudeOAuthRequestBody（不注入 metadata）。
     */
    public String normalizeClaudeOAuthRequestBody(String body, String model) {
        return normalizeClaudeOAuthRequestBody(body, model, false, null);
    }

    /**
     * Applies the post-normalization Claude OAuth body mimicry phases that
     * Sub2API runs after {@code normalizeClaudeOAuthRequestBody}:
     *
     * <ul>
     *   <li>rewrite mimicable tool names to Claude-Code-looking aliases;</li>
     *   <li>rewrite matching {@code tool_choice.name};</li>
     *   <li>rewrite historical {@code tool_use.name} blocks consistently;</li>
     *   <li>add the cache breakpoint to {@code tools[-1]}.</li>
     * </ul>
     *
     * <p>The optional Sub2API message cache-control rewrite phase is not
     * enabled by default because Sub2API's default is disabled. LandGate can
     * opt in with {@code landgate.gateway.anthropic.rewrite-message-cache-control=true}.</p>
     */
    public ClaudeOAuthBodyMimicryResult applyPostNormalizeClaudeOAuthMimicry(String body) {
        return applyPostNormalizeClaudeOAuthMimicry(body, rewriteMessageCacheControl);
    }

    public ClaudeOAuthBodyMimicryResult applyPostNormalizeClaudeOAuthMimicry(
            String body,
            boolean rewriteMessageCacheControl) {
        if (body == null || body.isBlank()) {
            return new ClaudeOAuthBodyMimicryResult(body, AnthropicToolNameRewrite.empty());
        }
        try {
            JsonNode root = JSON.readTree(body);
            if (!root.isObject()) {
                return new ClaudeOAuthBodyMimicryResult(body, AnthropicToolNameRewrite.empty());
            }
            ObjectNode rootObj = (ObjectNode) root;
            AnthropicToolNameRewrite rewrite = AnthropicToolNameRewrite.fromToolNames(collectMimicableToolNames(rootObj));
            boolean modified = false;

            if (rewriteMessageCacheControl) {
                modified |= rewriteMessageCacheControl(rootObj);
            }
            if (rewrite.hasRewrite()) {
                modified |= applyToolNameRewriteToBody(rootObj, rewrite);
            }
            modified |= applyToolsLastCacheBreakpoint(rootObj);

            return new ClaudeOAuthBodyMimicryResult(
                    modified ? JSON.writeValueAsString(rootObj) : body,
                    rewrite);
        } catch (Exception e) {
            log.warn("Failed to apply Claude OAuth post-normalize mimicry: {}", e.getMessage());
            return new ClaudeOAuthBodyMimicryResult(body, AnthropicToolNameRewrite.empty());
        }
    }

    public String applyAnthropicCacheControlTTL1hIfEnabled(String body) {
        return applyAnthropicCacheControlTTL1h(body, cacheTTL1hInjection);
    }

    public String applyAnthropicCacheControlTTL1h(String body, boolean enabled) {
        if (!enabled || body == null || body.isBlank()) {
            return body;
        }
        try {
            JsonNode root = JSON.readTree(body);
            if (!root.isObject()) {
                return body;
            }
            ObjectNode rootObj = (ObjectNode) root;
            boolean modified = forceEphemeralCacheControlTTL(rootObj, ClaudeConstants.CACHE_CONTROL_TTL_1H);
            return modified ? JSON.writeValueAsString(rootObj) : body;
        } catch (Exception e) {
            log.warn("Failed to apply Anthropic cache_control ttl=1h: {}", e.getMessage());
            return body;
        }
    }

    /**
     * 构建 OAuth 场景的新 metadata.user_id。
     * <p>
     * 参照：sub2api {@code buildOAuthMetadataUserID}。
     *
     * @param account 账号实体
     * @param fp      客户端指纹
     * @param body    Anthropic 请求 body（用于 session hash）
     * @return 新的 metadata.user_id 字符串
     */
    public String buildOAuthMetadataUserID(AccountEntity account, ClientFingerprint fp, String body) {
        if (account == null) return "";

        // 优先使用 account 上的 claudeUserId
        String userId = extractClaudeUserId(account);
        if (userId == null || userId.isEmpty()) {
            if (fp != null) {
                userId = fp.getClientId();
            }
        }
        if (userId == null || userId.isEmpty()) {
            userId = UUID.randomUUID().toString().replace("-", "");
        }

        // 从 body 提取 session hash
        String sessionHash = generateSessionHashFromBody(body, account.getId());
        String sessionId = UUID.randomUUID().toString();
        if (sessionHash != null && !sessionHash.isEmpty()) {
            sessionId = generateUUIDFromSeed(account.getId() + "::" + sessionHash);
        }

        String uaVersion = fp != null ? extractVersion(fp.getUserAgent()) : null;
        String accountUUID = extractAccountUUID(account);

        return MetadataUserIdParser.format(userId, accountUUID, sessionId, uaVersion);
    }

    /**
     * 应用 Claude Code 伪装请求头。
     * <p>
     * 强制设置标准 Claude Code CLI 头（覆盖客户端原始值），
     * 根据模型设置不同的 anthropic-beta 列表。
     *
     * @param builder  HTTP 请求构建器
     * @param isStream 是否流式请求
     * @param model    模型名（用于判断是否 haiku）
     */
    public void applyClaudeCodeMimicHeaders(HttpRequest.Builder builder, boolean isStream, String model) {
        applyClaudeCodeMimicHeaders(builder, isStream, model, Map.of());
    }

    public void applyClaudeCodeMimicHeaders(HttpRequest.Builder builder, boolean isStream, String model,
                                            Map<String, String> requestHeaders) {
        applyClaudeCodeMimicHeaders(builder, isStream, model, requestHeaders, Set.of());
    }

    public void applyClaudeCodeMimicHeaders(HttpRequest.Builder builder,
                                            boolean isStream,
                                            String model,
                                            Map<String, String> requestHeaders,
                                            Set<String> droppedBetas) {
        if (builder == null) return;

        // 强制标准 Claude Code CLI 头，顺序和 ClaudeConstants.DEFAULT_MIMICRY_HEADERS 保持一致。
        ClaudeConstants.DEFAULT_MIMICRY_HEADERS.forEach(builder::setHeader);

        if (isStream) {
            builder.setHeader(AnthropicApiProfile.HEADER_STAINLESS_HELPER_METHOD,
                    AnthropicApiProfile.STAINLESS_HELPER_METHOD_STREAM);
        }

        // anthropic-beta 头
        builder.setHeader(AnthropicApiProfile.HEADER_ANTHROPIC_BETA,
                AnthropicClaudeCodeProfile.mergeBetaHeader(
                        ClaudeConstants.requiredMimicryBetas(model),
                        GatewayHeaderPolicy.value(requestHeaders, AnthropicApiProfile.HEADER_ANTHROPIC_BETA),
                        droppedBetas));
    }

    /**
     * 应用基础 OAuth 头默认值（所有 OAuth 账号，无论是否伪装）。
     */
    public void applyOAuthHeaderDefaults(HttpRequest.Builder builder) {
        applyOAuthHeaderDefaults(builder, null, Map.of());
    }

    public void applyOAuthHeaderDefaults(HttpRequest.Builder builder, String model, Map<String, String> requestHeaders) {
        applyOAuthHeaderDefaults(builder, model, requestHeaders, Set.of());
    }

    public void applyOAuthHeaderDefaults(HttpRequest.Builder builder,
                                         String model,
                                         Map<String, String> requestHeaders,
                                         Set<String> droppedBetas) {
        if (builder == null) return;
        ClaudeConstants.DEFAULT_MIMICRY_HEADERS.forEach(builder::setHeader);
        builder.setHeader(AnthropicApiProfile.HEADER_ANTHROPIC_BETA,
                AnthropicClaudeCodeProfile.stripBetaTokens(
                        ClaudeConstants.ensureOAuthBetaHeader(model,
                                GatewayHeaderPolicy.value(requestHeaders, AnthropicApiProfile.HEADER_ANTHROPIC_BETA)),
                        droppedBetas));
    }

    /**
     * 从 body 构建 OAuth metadata.user_id 并注入到 body。
     * 组合 buildOAuthMetadataUserID + 注入逻辑。
     */
    public String buildAndInjectMetadataUserID(String body, AccountEntity account,
                                                ClientFingerprint fp) {
        return buildAndInjectMetadataUserID(body, account, fp, true);
    }

    public String buildAndInjectMetadataUserID(String body, AccountEntity account,
                                                ClientFingerprint fp,
                                                boolean stripSystemCacheControl) {
        if (hasMetadataUserId(body)) {
            return body;
        }
        String metadataUserId = buildOAuthMetadataUserID(account, fp, body);
        if (metadataUserId == null || metadataUserId.isEmpty()) return body;
        return normalizeClaudeOAuthRequestBody(body, "", true, metadataUserId, stripSystemCacheControl);
    }

    private static boolean rewriteMessageCacheControl(ObjectNode rootObj) {
        boolean stripped = stripMessageCacheControl(rootObj);
        boolean added = addMessageCacheBreakpoints(rootObj);
        return stripped || added;
    }

    private static boolean stripMessageCacheControl(ObjectNode rootObj) {
        JsonNode messages = rootObj.get(AnthropicMessagesBodyPolicy.FIELD_MESSAGES);
        if (messages == null || !messages.isArray()) {
            return false;
        }
        boolean modified = false;
        for (JsonNode message : messages) {
            JsonNode content = message.get(AnthropicMessagesBodyPolicy.FIELD_CONTENT);
            if (content == null || !content.isArray()) {
                continue;
            }
            for (JsonNode block : content) {
                if (AnthropicMessagesBodyPolicy.hasCacheControl(block)) {
                    ((ObjectNode) block).remove(AnthropicMessagesBodyPolicy.FIELD_CACHE_CONTROL);
                    modified = true;
                }
            }
        }
        return modified;
    }

    private static boolean addMessageCacheBreakpoints(ObjectNode rootObj) {
        JsonNode messages = rootObj.get(AnthropicMessagesBodyPolicy.FIELD_MESSAGES);
        if (messages == null || !messages.isArray() || messages.size() == 0) {
            return false;
        }

        boolean modified = injectCacheControlOnLastContentBlock(messages.get(messages.size() - 1));
        if (messages.size() >= 4) {
            int userCount = 0;
            for (int i = messages.size() - 1; i >= 0; i--) {
                JsonNode message = messages.get(i);
                if (!AnthropicMessagesBodyPolicy.ROLE_USER.equals(
                        message.path(AnthropicMessagesBodyPolicy.FIELD_ROLE).asText(""))) {
                    continue;
                }
                userCount++;
                if (userCount == 2) {
                    modified |= injectCacheControlOnLastContentBlock(message);
                    break;
                }
            }
        }
        return modified;
    }

    private static boolean injectCacheControlOnLastContentBlock(JsonNode message) {
        if (message == null || !message.isObject()) {
            return false;
        }
        ObjectNode messageObj = (ObjectNode) message;
        JsonNode content = messageObj.get(AnthropicMessagesBodyPolicy.FIELD_CONTENT);
        if (content != null && content.isTextual()) {
            ObjectNode textBlock = JSON.createObjectNode();
            textBlock.put(AnthropicMessagesBodyPolicy.FIELD_TYPE, AnthropicMessagesBodyPolicy.TYPE_TEXT);
            textBlock.put(AnthropicMessagesBodyPolicy.FIELD_TEXT, content.asText());
            textBlock.set(AnthropicMessagesBodyPolicy.FIELD_CACHE_CONTROL, newEphemeralCacheControl());
            ArrayNode contentArray = JSON.createArrayNode();
            contentArray.add(textBlock);
            messageObj.set(AnthropicMessagesBodyPolicy.FIELD_CONTENT, contentArray);
            return true;
        }
        if (content == null || !content.isArray() || content.size() == 0) {
            return false;
        }
        JsonNode lastBlock = content.get(content.size() - 1);
        if (!lastBlock.isObject()) {
            return false;
        }
        ObjectNode lastBlockObj = (ObjectNode) lastBlock;
        JsonNode existing = lastBlockObj.get(AnthropicMessagesBodyPolicy.FIELD_CACHE_CONTROL);
        if (existing != null && existing.isObject()) {
            if (existing.path(AnthropicMessagesBodyPolicy.FIELD_TTL).asText("").isBlank()) {
                ((ObjectNode) existing).put(AnthropicMessagesBodyPolicy.FIELD_TTL,
                        ClaudeConstants.DEFAULT_CACHE_CONTROL_TTL);
                return true;
            }
            return false;
        }
        lastBlockObj.set(AnthropicMessagesBodyPolicy.FIELD_CACHE_CONTROL, newEphemeralCacheControl());
        return true;
    }

    private static boolean forceEphemeralCacheControlTTL(ObjectNode rootObj, String ttl) {
        boolean modified = setCacheControlTTLIfEphemeral(rootObj, ttl);

        JsonNode system = rootObj.get(AnthropicMessagesBodyPolicy.FIELD_SYSTEM);
        if (system != null && system.isArray()) {
            for (JsonNode block : system) {
                modified |= setCacheControlTTLIfEphemeral(block, ttl);
            }
        }

        JsonNode messages = rootObj.get(AnthropicMessagesBodyPolicy.FIELD_MESSAGES);
        if (messages != null && messages.isArray()) {
            for (JsonNode message : messages) {
                JsonNode content = message.get(AnthropicMessagesBodyPolicy.FIELD_CONTENT);
                if (content == null || !content.isArray()) {
                    continue;
                }
                for (JsonNode block : content) {
                    modified |= setCacheControlTTLIfEphemeral(block, ttl);
                }
            }
        }

        JsonNode tools = rootObj.get(AnthropicMessagesBodyPolicy.FIELD_TOOLS);
        if (tools != null && tools.isArray()) {
            for (JsonNode tool : tools) {
                modified |= setCacheControlTTLIfEphemeral(tool, ttl);
            }
        }

        return modified;
    }

    private static boolean setCacheControlTTLIfEphemeral(JsonNode node, String ttl) {
        if (node == null || !node.isObject() || ttl == null || ttl.isBlank()) {
            return false;
        }
        JsonNode cacheControl = node.get(AnthropicMessagesBodyPolicy.FIELD_CACHE_CONTROL);
        if (cacheControl == null || !cacheControl.isObject()) {
            return false;
        }
        if (!AnthropicMessagesBodyPolicy.CACHE_CONTROL_TYPE_EPHEMERAL.equals(
                cacheControl.path(AnthropicMessagesBodyPolicy.FIELD_TYPE).asText(""))) {
            return false;
        }
        if (ttl.equals(cacheControl.path(AnthropicMessagesBodyPolicy.FIELD_TTL).asText(""))) {
            return false;
        }
        ((ObjectNode) cacheControl).put(AnthropicMessagesBodyPolicy.FIELD_TTL, ttl);
        return true;
    }

    private static List<String> collectMimicableToolNames(ObjectNode rootObj) {
        List<String> names = new ArrayList<>();
        JsonNode tools = rootObj.get(AnthropicMessagesBodyPolicy.FIELD_TOOLS);
        if (tools == null || !tools.isArray()) {
            return names;
        }
        for (JsonNode tool : tools) {
            if (!tool.isObject()) {
                continue;
            }
            String type = tool.path(AnthropicMessagesBodyPolicy.FIELD_TYPE).asText("");
            if (!AnthropicOAuthToolNamePolicy.shouldMimicToolName(type)) {
                continue;
            }
            String name = tool.path(AnthropicMessagesBodyPolicy.FIELD_NAME).asText("");
            if (!name.isBlank()) {
                names.add(name);
            }
        }
        return names;
    }

    private static boolean applyToolNameRewriteToBody(ObjectNode rootObj, AnthropicToolNameRewrite rewrite) {
        boolean modified = false;
        JsonNode tools = rootObj.get(AnthropicMessagesBodyPolicy.FIELD_TOOLS);
        if (tools != null && tools.isArray()) {
            for (JsonNode tool : tools) {
                if (!tool.isObject()) {
                    continue;
                }
                String type = tool.path(AnthropicMessagesBodyPolicy.FIELD_TYPE).asText("");
                if (!AnthropicOAuthToolNamePolicy.shouldMimicToolName(type)) {
                    continue;
                }
                String fake = rewrite.fakeName(tool.path(AnthropicMessagesBodyPolicy.FIELD_NAME).asText(""));
                if (fake != null && !fake.isBlank()) {
                    ((ObjectNode) tool).put(AnthropicMessagesBodyPolicy.FIELD_NAME, fake);
                    modified = true;
                }
            }
        }

        JsonNode toolChoice = rootObj.get(AnthropicMessagesBodyPolicy.FIELD_TOOL_CHOICE);
        if (toolChoice != null && toolChoice.isObject()
                && AnthropicMessagesBodyPolicy.TYPE_TOOL_CHOICE_TOOL.equals(
                toolChoice.path(AnthropicMessagesBodyPolicy.FIELD_TYPE).asText(""))) {
            String fake = rewrite.fakeName(toolChoice.path(AnthropicMessagesBodyPolicy.FIELD_NAME).asText(""));
            if (fake != null && !fake.isBlank()) {
                ((ObjectNode) toolChoice).put(AnthropicMessagesBodyPolicy.FIELD_NAME, fake);
                modified = true;
            }
        }

        JsonNode messages = rootObj.get(AnthropicMessagesBodyPolicy.FIELD_MESSAGES);
        if (messages != null && messages.isArray()) {
            for (JsonNode message : messages) {
                JsonNode content = message.get(AnthropicMessagesBodyPolicy.FIELD_CONTENT);
                if (content == null || !content.isArray()) {
                    continue;
                }
                for (JsonNode block : content) {
                    if (!block.isObject()
                            || !AnthropicMessagesBodyPolicy.TYPE_TOOL_USE.equals(
                            block.path(AnthropicMessagesBodyPolicy.FIELD_TYPE).asText(""))) {
                        continue;
                    }
                    String fake = rewrite.fakeName(block.path(AnthropicMessagesBodyPolicy.FIELD_NAME).asText(""));
                    if (fake != null && !fake.isBlank()) {
                        ((ObjectNode) block).put(AnthropicMessagesBodyPolicy.FIELD_NAME, fake);
                        modified = true;
                    }
                }
            }
        }
        return modified;
    }

    private static boolean applyToolsLastCacheBreakpoint(ObjectNode rootObj) {
        JsonNode tools = rootObj.get(AnthropicMessagesBodyPolicy.FIELD_TOOLS);
        if (tools == null || !tools.isArray() || tools.size() == 0) {
            return false;
        }
        JsonNode last = tools.get(tools.size() - 1);
        if (!last.isObject()) {
            return false;
        }

        ObjectNode lastObj = (ObjectNode) last;
        JsonNode existing = lastObj.get(AnthropicMessagesBodyPolicy.FIELD_CACHE_CONTROL);
        if (existing != null && existing.isObject()) {
            if (existing.path(AnthropicMessagesBodyPolicy.FIELD_TTL).asText("").isBlank()) {
                ((ObjectNode) existing).put(AnthropicMessagesBodyPolicy.FIELD_TTL,
                        ClaudeConstants.DEFAULT_CACHE_CONTROL_TTL);
                return true;
            }
            return false;
        }

        ObjectNode cacheControl = JSON.createObjectNode();
        lastObj.set(AnthropicMessagesBodyPolicy.FIELD_CACHE_CONTROL, newEphemeralCacheControl());
        return true;
    }

    private static ObjectNode newEphemeralCacheControl() {
        ObjectNode cacheControl = JSON.createObjectNode();
        cacheControl.put(AnthropicMessagesBodyPolicy.FIELD_TYPE,
                AnthropicMessagesBodyPolicy.CACHE_CONTROL_TYPE_EPHEMERAL);
        cacheControl.put(AnthropicMessagesBodyPolicy.FIELD_TTL,
                ClaudeConstants.DEFAULT_CACHE_CONTROL_TTL);
        return cacheControl;
    }

    // ========================
    // 辅助方法
    // ========================

    /**
     * 从请求 body 提取 system 文本（支持 string 和 content block 数组）。
     */
    private static String extractSystemText(JsonNode root) {
        if (!root.has(AnthropicMessagesBodyPolicy.FIELD_SYSTEM)) return "";
        JsonNode system = root.get(AnthropicMessagesBodyPolicy.FIELD_SYSTEM);
        if (system.isTextual()) return system.asText().trim();
        if (system.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode item : system) {
                if (item.isObject() && AnthropicMessagesBodyPolicy.TYPE_TEXT.equals(
                        item.has(AnthropicMessagesBodyPolicy.FIELD_TYPE)
                                ? item.get(AnthropicMessagesBodyPolicy.FIELD_TYPE).asText()
                                : "")
                        && item.has(AnthropicMessagesBodyPolicy.FIELD_TEXT)) {
                    String text = item.get(AnthropicMessagesBodyPolicy.FIELD_TEXT).asText().trim();
                    if (!text.isEmpty()) {
                        if (sb.length() > 0) sb.append("\n\n");
                        sb.append(text);
                    }
                }
            }
            return sb.toString();
        }
        return "";
    }

    private static boolean hasMetadataUserId(String body) {
        if (body == null || body.isBlank()) {
            return false;
        }
        try {
            JsonNode root = JSON.readTree(body);
            JsonNode metadata = root.get(AnthropicMessagesBodyPolicy.FIELD_METADATA);
            return metadata != null
                    && metadata.isObject()
                    && metadata.has(AnthropicMessagesBodyPolicy.FIELD_USER_ID)
                    && metadata.get(AnthropicMessagesBodyPolicy.FIELD_USER_ID).isTextual()
                    && !metadata.get(AnthropicMessagesBodyPolicy.FIELD_USER_ID).asText().isBlank();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 标准化 system 文本（替换 OpenCode 标识）。
     */
    private static String sanitizeSystemText(String text) {
        if (text == null || text.isEmpty()) return text;
        return text.replace(
                "You are OpenCode, the best coding agent on the planet.",
                ClaudeConstants.CLAUDE_CODE_SYSTEM_PROMPT.trim());
    }

    /**
     * 从 User-Agent 中提取版本号。
     */
    private static String extractVersion(String ua) {
        if (ua == null) return "";
        java.util.regex.Matcher m = ClaudeConstants.CLAUDE_CLI_UA_PATTERN.matcher(ua);
        if (m.find()) {
            String matched = m.group();
            int slash = matched.indexOf('/');
            if (slash >= 0) return matched.substring(slash + 1);
        }
        return "";
    }

    /**
     * 提取账号的 claude_user_id（从 extra JSON 中读取）。
     */
    private static String extractClaudeUserId(AccountEntity account) {
        try {
            if (account.getExtra() != null && !account.getExtra().equals("{}")) {
                JsonNode extra = JSON.readTree(account.getExtra());
                if (extra.has("claude_user_id")) {
                    return extra.get("claude_user_id").asText().trim();
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return "";
    }

    /**
     * 提取账号的 account_uuid（从 extra JSON 中读取）。
     */
    private static String extractAccountUUID(AccountEntity account) {
        try {
            if (account.getExtra() != null && !account.getExtra().equals("{}")) {
                JsonNode extra = JSON.readTree(account.getExtra());
                if (extra.has("account_uuid")) {
                    return extra.get("account_uuid").asText().trim();
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return "";
    }

    /**
     * 从 body 中提取 session hash（取第一条 user 消息的 SHA256）。
     */
    private static String generateSessionHashFromBody(String body, Long accountId) {
        try {
            String firstUserText = BillingHeaderService.extractFirstUserText(body);
            if (firstUserText != null && !firstUserText.isEmpty()) {
                java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
                byte[] hash = md.digest(firstUserText.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                return bytesToHex(hash).substring(0, 16);
            }
        } catch (Exception e) {
            // ignore
        }
        return "";
    }

    /**
     * 从 seed 生成 UUID 格式字符串。
     */
    private static String generateUUIDFromSeed(String seed) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(seed.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 16; i++) {
                hex.append(String.format("%02x", hash[i]));
            }
            String h = hex.toString();
            return h.substring(0, 8) + "-" + h.substring(8, 12) + "-"
                    + h.substring(12, 16) + "-" + h.substring(16, 20) + "-" + h.substring(20, 32);
        } catch (Exception e) {
            return UUID.randomUUID().toString();
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
