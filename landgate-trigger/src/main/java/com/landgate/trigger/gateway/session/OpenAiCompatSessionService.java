package com.landgate.trigger.gateway.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.landgate.domain.account.model.entity.AccountEntity;
import com.landgate.trigger.gateway.converter.compat.CompatPromptCacheKeyPolicy;
import com.landgate.types.enums.AccountType;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RMapCache;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * OpenAI Responses compatibility session state.
 * <p>
 * Mirrors sub2api's OpenAI Anthropic-compat state:
 * OAuth/Codex uses stable {@code session_id} plus {@code x-codex-turn-state};
 * API-key Responses uses {@code previous_response_id} continuation and trims the
 * replay to the latest turn.
 */
@Slf4j
@Component
public class OpenAiCompatSessionService {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long TTL_MINUTES = 30;
    private static final String RESPONSE_ID_CACHE = "openai:compat:response-id";
    private static final String TURN_STATE_CACHE = "openai:compat:turn-state";
    private static final String CONTINUATION_DISABLED_CACHE = "openai:compat:continuation-disabled";
    private static final String DIGEST_CACHE = "openai:compat:anthropic-digest";

    private final RMapCache<String, String> responseIdCache;
    private final RMapCache<String, String> turnStateCache;
    private final RMapCache<String, Boolean> continuationDisabledCache;
    private final RMapCache<String, String> digestCache;

    @Autowired
    public OpenAiCompatSessionService(RedissonClient redissonClient) {
        this(
                redissonClient.getMapCache(RESPONSE_ID_CACHE),
                redissonClient.getMapCache(TURN_STATE_CACHE),
                redissonClient.getMapCache(CONTINUATION_DISABLED_CACHE),
                redissonClient.getMapCache(DIGEST_CACHE)
        );
    }

    OpenAiCompatSessionService(RMapCache<String, String> responseIdCache,
                               RMapCache<String, String> turnStateCache,
                               RMapCache<String, Boolean> continuationDisabledCache,
                               RMapCache<String, String> digestCache) {
        this.responseIdCache = responseIdCache;
        this.turnStateCache = turnStateCache;
        this.continuationDisabledCache = continuationDisabledCache;
        this.digestCache = digestCache;
    }

    public CompatState prepareAnthropicMessagesCompat(AccountEntity account,
                                                      Long apiKeyId,
                                                      String clientBody,
                                                      String responsesBody,
                                                      String requestedModel) {
        if (account == null || responsesBody == null || responsesBody.isBlank()) {
            return CompatState.empty(responsesBody);
        }

        String promptCacheKey = CompatPromptCacheKeyPolicy.extractPromptCacheKey(responsesBody);
        String digestChain = "";
        String matchedDigestChain = "";
        boolean injected = false;

        JsonNode anthropic = parse(clientBody);
        String model = firstNonBlank(extractText(anthropic, "model"), requestedModel, extractResponsesModel(responsesBody));
        if (promptCacheKey.isBlank() && CompatPromptCacheKeyPolicy.shouldAutoInjectPromptCacheKeyForCompat(model)) {
            promptCacheKey = CompatPromptCacheKeyPolicy.promptCacheKeyFromAnthropicMetadataSession(anthropic);
            if (promptCacheKey.isBlank()) {
                promptCacheKey = CompatPromptCacheKeyPolicy.deriveAnthropicCacheControlPromptCacheKey(anthropic);
            }
            if (promptCacheKey.isBlank()) {
                digestChain = CompatPromptCacheKeyPolicy.buildOpenAICompatAnthropicDigestChain(anthropic);
                DigestMatch match = findAnthropicDigestPromptCacheKey(account, apiKeyId, digestChain);
                if (!match.promptCacheKey().isBlank()) {
                    promptCacheKey = match.promptCacheKey();
                    matchedDigestChain = match.matchedDigestChain();
                } else {
                    promptCacheKey = CompatPromptCacheKeyPolicy.promptCacheKeyFromAnthropicDigest(digestChain);
                }
            }
            if (!promptCacheKey.isBlank()) {
                responsesBody = CompatPromptCacheKeyPolicy.injectPromptCacheKey(responsesBody, promptCacheKey);
                injected = true;
            }
        } else if (!promptCacheKey.isBlank()) {
            digestChain = CompatPromptCacheKeyPolicy.buildOpenAICompatAnthropicDigestChain(anthropic);
        }

        responsesBody = ensureResponsesInstructionsField(responsesBody);
        if (account.getType() == AccountType.API_KEY) {
            responsesBody = removeOpenAiApiKeyUnsupportedResponseFields(responsesBody);
        }

        String previousResponseId = "";
        boolean continuationDisabled = false;
        if (account.getType() == AccountType.API_KEY
                && !promptCacheKey.isBlank()
                && CompatPromptCacheKeyPolicy.shouldAutoInjectPromptCacheKeyForCompat(model)) {
            continuationDisabled = isContinuationDisabled(account, apiKeyId, promptCacheKey);
            if (!continuationDisabled) {
                previousResponseId = getResponseId(account, apiKeyId, promptCacheKey);
                if (!previousResponseId.isBlank()) {
                    responsesBody = attachPreviousResponseIdAndTrim(responsesBody, previousResponseId);
                }
            }
        }

        return new CompatState(
                responsesBody,
                promptCacheKey,
                digestChain,
                matchedDigestChain,
                previousResponseId,
                continuationDisabled,
                injected
        );
    }

    public String getTurnState(AccountEntity account, Long apiKeyId, String promptCacheKey) {
        String key = sessionKey(account, apiKeyId, promptCacheKey);
        if (key.isBlank() || turnStateCache == null) return "";
        String value = trim(turnStateCache.get(key));
        if (!value.isBlank()) {
            turnStateCache.put(key, value, TTL_MINUTES, TimeUnit.MINUTES);
        }
        return value;
    }

    public void bindTurnState(AccountEntity account, Long apiKeyId, String promptCacheKey, String turnState) {
        String key = sessionKey(account, apiKeyId, promptCacheKey);
        String state = trim(turnState);
        if (key.isBlank() || state.isBlank() || turnStateCache == null) return;
        turnStateCache.put(key, state, TTL_MINUTES, TimeUnit.MINUTES);
    }

    public String getResponseId(AccountEntity account, Long apiKeyId, String promptCacheKey) {
        if (isContinuationDisabled(account, apiKeyId, promptCacheKey)) return "";
        String key = sessionKey(account, apiKeyId, promptCacheKey);
        if (key.isBlank() || responseIdCache == null) return "";
        String value = trim(responseIdCache.get(key));
        if (!value.isBlank()) {
            responseIdCache.put(key, value, TTL_MINUTES, TimeUnit.MINUTES);
        }
        return value;
    }

    public void bindResponseId(AccountEntity account, Long apiKeyId, String promptCacheKey, String responseId) {
        String key = sessionKey(account, apiKeyId, promptCacheKey);
        String id = trim(responseId);
        if (key.isBlank() || id.isBlank() || responseIdCache == null) return;
        if (isContinuationDisabled(account, apiKeyId, promptCacheKey)) {
            responseIdCache.remove(key);
            return;
        }
        responseIdCache.put(key, id, TTL_MINUTES, TimeUnit.MINUTES);
    }

    public void deleteResponseId(AccountEntity account, Long apiKeyId, String promptCacheKey) {
        String key = sessionKey(account, apiKeyId, promptCacheKey);
        if (key.isBlank() || responseIdCache == null) return;
        responseIdCache.remove(key);
    }

    public void disableContinuation(AccountEntity account, Long apiKeyId, String promptCacheKey) {
        String key = sessionKey(account, apiKeyId, promptCacheKey);
        if (key.isBlank() || continuationDisabledCache == null) return;
        continuationDisabledCache.put(key, Boolean.TRUE, TTL_MINUTES, TimeUnit.MINUTES);
        if (responseIdCache != null) responseIdCache.remove(key);
    }

    public boolean isContinuationDisabled(AccountEntity account, Long apiKeyId, String promptCacheKey) {
        String key = sessionKey(account, apiKeyId, promptCacheKey);
        if (key.isBlank() || continuationDisabledCache == null) return false;
        Boolean disabled = continuationDisabledCache.get(key);
        if (Boolean.TRUE.equals(disabled)) {
            continuationDisabledCache.put(key, Boolean.TRUE, TTL_MINUTES, TimeUnit.MINUTES);
            return true;
        }
        return false;
    }

    public DigestMatch findAnthropicDigestPromptCacheKey(AccountEntity account, Long apiKeyId, String digestChain) {
        String ns = digestNamespace(account, apiKeyId);
        String chain = trim(digestChain);
        if (ns.isBlank() || chain.isBlank() || digestCache == null) return DigestMatch.empty();
        while (!chain.isBlank()) {
            String key = ns + chain;
            String promptCacheKey = trim(digestCache.get(key));
            if (!promptCacheKey.isBlank()) {
                digestCache.put(key, promptCacheKey, TTL_MINUTES, TimeUnit.MINUTES);
                return new DigestMatch(promptCacheKey, chain);
            }
            int i = chain.lastIndexOf("-");
            if (i < 0) break;
            chain = chain.substring(0, i);
        }
        return DigestMatch.empty();
    }

    public void bindAnthropicDigestPromptCacheKey(AccountEntity account,
                                                  Long apiKeyId,
                                                  String digestChain,
                                                  String promptCacheKey,
                                                  String oldDigestChain) {
        String ns = digestNamespace(account, apiKeyId);
        String chain = trim(digestChain);
        String key = trim(promptCacheKey);
        if (ns.isBlank() || chain.isBlank() || key.isBlank() || digestCache == null) return;
        digestCache.put(ns + chain, key, TTL_MINUTES, TimeUnit.MINUTES);
        String old = trim(oldDigestChain);
        if (!old.isBlank() && !old.equals(chain)) {
            digestCache.remove(ns + old);
        }
    }

    private static String attachPreviousResponseIdAndTrim(String responsesBody, String previousResponseId) {
        try {
            JsonNode parsed = JSON.readTree(responsesBody);
            if (!(parsed instanceof ObjectNode root)) return responsesBody;
            root.put("previous_response_id", previousResponseId);
            trimResponsesInputToLatestTurn(root);
            return JSON.writeValueAsString(root);
        } catch (Exception ignored) {
            return responsesBody;
        }
    }

    private static String ensureResponsesInstructionsField(String responsesBody) {
        if (responsesBody == null || responsesBody.isBlank()) return responsesBody;
        try {
            JsonNode parsed = JSON.readTree(responsesBody);
            if (!(parsed instanceof ObjectNode root)) return responsesBody;
            JsonNode instructions = root.get("instructions");
            if (instructions != null && instructions.isTextual()) {
                return responsesBody;
            }
            root.put("instructions", "");
            return JSON.writeValueAsString(root);
        } catch (Exception ignored) {
            return responsesBody;
        }
    }

    private static String removeOpenAiApiKeyUnsupportedResponseFields(String responsesBody) {
        if (responsesBody == null || responsesBody.isBlank()) return responsesBody;
        try {
            JsonNode parsed = JSON.readTree(responsesBody);
            if (!(parsed instanceof ObjectNode root)) return responsesBody;
            boolean changed = false;
            if (root.has("max_output_tokens")) {
                root.remove("max_output_tokens");
                changed = true;
            }
            if (root.has("max_completion_tokens")) {
                root.remove("max_completion_tokens");
                changed = true;
            }
            return changed ? JSON.writeValueAsString(root) : responsesBody;
        } catch (Exception ignored) {
            return responsesBody;
        }
    }

    private static void trimResponsesInputToLatestTurn(ObjectNode root) {
        JsonNode input = root.get("input");
        if (input == null || !input.isArray() || input.size() == 0) return;
        int start = latestTurnStart((ArrayNode) input);
        if (start <= 0) return;
        ArrayNode trimmed = JSON.createArrayNode();
        for (int i = start; i < input.size(); i++) {
            trimmed.add(input.get(i));
        }
        root.set("input", trimmed);
    }

    private static int latestTurnStart(ArrayNode items) {
        int start = items.size() - 1;
        JsonNode last = items.get(start);
        String type = last.path("type").asText("");
        String role = last.path("role").asText("");
        if ("function_call_output".equals(type)) {
            while (start > 0 && "function_call_output".equals(items.get(start - 1).path("type").asText(""))) {
                start--;
            }
        } else if ("message".equals(type) && "user".equals(role)) {
            while (start > 0 && "function_call_output".equals(items.get(start - 1).path("type").asText(""))) {
                start--;
            }
        } else {
            return start;
        }
        return expandToolCallStart(items, start);
    }

    private static int expandToolCallStart(ArrayNode items, int start) {
        Set<String> neededCallIds = new HashSet<>();
        for (int i = start; i < items.size(); i++) {
            if (!"function_call_output".equals(items.get(i).path("type").asText(""))) continue;
            String callId = items.get(i).path("call_id").asText("").trim();
            if (!callId.isBlank()) neededCallIds.add(callId);
        }
        if (neededCallIds.isEmpty()) return start;

        int expanded = start;
        for (int i = start - 1; i >= 0 && !neededCallIds.isEmpty(); i--) {
            JsonNode item = items.get(i);
            if (!"function_call".equals(item.path("type").asText(""))) continue;
            String callId = item.path("call_id").asText("").trim();
            if (neededCallIds.remove(callId)) {
                expanded = i;
            }
        }
        return expanded;
    }

    private static JsonNode parse(String body) {
        if (body == null || body.isBlank()) return JSON.createObjectNode();
        try {
            return JSON.readTree(body);
        } catch (Exception ignored) {
            return JSON.createObjectNode();
        }
    }

    private static String extractResponsesModel(String body) {
        JsonNode parsed = parse(body);
        return extractText(parsed, "model");
    }

    private static String extractText(JsonNode root, String field) {
        if (root == null || root.isMissingNode() || field == null) return "";
        JsonNode value = root.get(field);
        return value != null && value.isTextual() ? value.asText().trim() : "";
    }

    private static String sessionKey(AccountEntity account, Long apiKeyId, String promptCacheKey) {
        if (account == null || account.getId() == null || trim(promptCacheKey).isBlank()) return "";
        return account.getId() + "\u0000" + (apiKeyId == null ? 0L : apiKeyId) + "\u0000" + trim(promptCacheKey);
    }

    private static String digestNamespace(AccountEntity account, Long apiKeyId) {
        if (account == null || account.getId() == null || account.getId() <= 0) return "";
        return account.getId() + "|" + (apiKeyId == null ? 0L : apiKeyId) + "|";
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return "";
        for (String value : values) {
            if (value != null && !value.isBlank()) return value.trim();
        }
        return "";
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    public record CompatState(
            String body,
            String promptCacheKey,
            String digestChain,
            String matchedDigestChain,
            String previousResponseId,
            boolean continuationDisabled,
            boolean promptCacheKeyInjected
    ) {
        static CompatState empty(String body) {
            return new CompatState(body, "", "", "", "", false, false);
        }
    }

    public record DigestMatch(String promptCacheKey, String matchedDigestChain) {
        static DigestMatch empty() {
            return new DigestMatch("", "");
        }
    }
}
