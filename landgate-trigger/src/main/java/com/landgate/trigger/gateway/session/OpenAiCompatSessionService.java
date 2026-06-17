package com.landgate.trigger.gateway.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.landgate.domain.account.model.entity.AccountEntity;
import com.landgate.trigger.gateway.compat.OpenAiCompatTodoGuard;
import com.landgate.types.gateway.AnthropicMessagesBodyPolicy;
import com.landgate.types.gateway.CompatPromptCacheKeyPolicy;
import com.landgate.types.gateway.OpenAiAccountAuthPolicy;
import com.landgate.types.gateway.OpenAiCompatModelPolicy;
import com.landgate.types.gateway.OpenAiCompatSessionPolicy;
import com.landgate.types.gateway.OpenAiResponsesBodyPolicy;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RMapCache;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

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

    private final RMapCache<String, String> responseIdCache;
    private final RMapCache<String, String> turnStateCache;
    private final RMapCache<String, Boolean> continuationDisabledCache;
    private final RMapCache<String, String> digestCache;
    private final OpenAiAnthropicReplayGuard replayGuard = new OpenAiAnthropicReplayGuard();

    @Autowired
    public OpenAiCompatSessionService(RedissonClient redissonClient) {
        this(
                redissonClient.getMapCache(OpenAiCompatSessionPolicy.RESPONSE_ID_CACHE),
                redissonClient.getMapCache(OpenAiCompatSessionPolicy.TURN_STATE_CACHE),
                redissonClient.getMapCache(OpenAiCompatSessionPolicy.CONTINUATION_DISABLED_CACHE),
                redissonClient.getMapCache(OpenAiCompatSessionPolicy.DIGEST_CACHE)
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
        String model = resolveCompatModel(account, anthropic, requestedModel, extractResponsesModel(responsesBody));
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

        responsesBody = OpenAiCompatResponsesBodyNormalizer.ensureInstructionsField(responsesBody);
        if (OpenAiAccountAuthPolicy.isApiKeyType(account.getType())) {
            responsesBody = OpenAiCompatResponsesBodyNormalizer.removeOpenAiApiKeyUnsupportedResponseFields(
                    responsesBody);
        }

        String previousResponseId = "";
        boolean continuationDisabled = false;
        if (OpenAiAccountAuthPolicy.isApiKeyType(account.getType())
                && !promptCacheKey.isBlank()
                && CompatPromptCacheKeyPolicy.shouldAutoInjectPromptCacheKeyForCompat(model)) {
            continuationDisabled = isContinuationDisabled(account, apiKeyId, promptCacheKey);
            if (!continuationDisabled) {
                previousResponseId = getResponseId(account, apiKeyId, promptCacheKey);
                if (!previousResponseId.isBlank()) {
                    responsesBody = OpenAiCompatResponsesBodyNormalizer.attachPreviousResponseIdAndTrim(
                            responsesBody, previousResponseId);
                }
            }
        }
        if (OpenAiAccountAuthPolicy.isApiKeyType(account.getType())
                && CompatPromptCacheKeyPolicy.shouldAutoInjectPromptCacheKeyForCompat(model)) {
            responsesBody = OpenAiCompatTodoGuard.appendToResponsesBody(responsesBody).body();
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

    public FullReplayTrimState trimAnthropicMessagesFullReplayForApiKeyCompat(AccountEntity account,
                                                                              Long apiKeyId,
                                                                              String clientBody,
                                                                              String requestedModel) {
        if (account == null || !OpenAiAccountAuthPolicy.isApiKeyType(account.getType())
                || clientBody == null || clientBody.isBlank()) {
            return new FullReplayTrimState(clientBody, false, 0);
        }

        JsonNode anthropic = parse(clientBody);
        String model = resolveCompatModel(account, anthropic, requestedModel, "");
        if (!CompatPromptCacheKeyPolicy.shouldAutoInjectPromptCacheKeyForCompat(model)) {
            return new FullReplayTrimState(clientBody, false, messageCount(anthropic));
        }

        String promptCacheKey = resolveAnthropicCompatPromptCacheKey(account, apiKeyId, anthropic, model);
        if (!promptCacheKey.isBlank()
                && (isContinuationDisabled(account, apiKeyId, promptCacheKey)
                || !getResponseId(account, apiKeyId, promptCacheKey).isBlank())) {
            return new FullReplayTrimState(clientBody, false, messageCount(anthropic));
        }

        OpenAiAnthropicReplayGuard.TrimResult result = replayGuard.trimFullReplay(clientBody);
        return new FullReplayTrimState(result.body(), result.trimmed(), result.messagesAfterTrim());
    }

    public String getTurnState(AccountEntity account, Long apiKeyId, String promptCacheKey) {
        String key = sessionKey(account, apiKeyId, promptCacheKey);
        if (key.isBlank() || turnStateCache == null) return "";
        String value = trim(turnStateCache.get(key));
        if (!value.isBlank()) {
            refresh(turnStateCache, key, value);
        }
        return value;
    }

    public void bindTurnState(AccountEntity account, Long apiKeyId, String promptCacheKey, String turnState) {
        String key = sessionKey(account, apiKeyId, promptCacheKey);
        String state = trim(turnState);
        if (key.isBlank() || state.isBlank() || turnStateCache == null) return;
        refresh(turnStateCache, key, state);
    }

    public String getResponseId(AccountEntity account, Long apiKeyId, String promptCacheKey) {
        if (isContinuationDisabled(account, apiKeyId, promptCacheKey)) return "";
        String key = sessionKey(account, apiKeyId, promptCacheKey);
        if (key.isBlank() || responseIdCache == null) return "";
        String value = trim(responseIdCache.get(key));
        if (!value.isBlank()) {
            refresh(responseIdCache, key, value);
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
        refresh(responseIdCache, key, id);
    }

    public void deleteResponseId(AccountEntity account, Long apiKeyId, String promptCacheKey) {
        String key = sessionKey(account, apiKeyId, promptCacheKey);
        if (key.isBlank() || responseIdCache == null) return;
        responseIdCache.remove(key);
    }

    public void disableContinuation(AccountEntity account, Long apiKeyId, String promptCacheKey) {
        String key = sessionKey(account, apiKeyId, promptCacheKey);
        if (key.isBlank() || continuationDisabledCache == null) return;
        refresh(continuationDisabledCache, key, Boolean.TRUE);
        if (responseIdCache != null) responseIdCache.remove(key);
    }

    public boolean isContinuationDisabled(AccountEntity account, Long apiKeyId, String promptCacheKey) {
        String key = sessionKey(account, apiKeyId, promptCacheKey);
        if (key.isBlank() || continuationDisabledCache == null) return false;
        Boolean disabled = continuationDisabledCache.get(key);
        if (Boolean.TRUE.equals(disabled)) {
            refresh(continuationDisabledCache, key, Boolean.TRUE);
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
                refresh(digestCache, key, promptCacheKey);
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
        refresh(digestCache, ns + chain, key);
        String old = trim(oldDigestChain);
        if (!old.isBlank() && !old.equals(chain)) {
            digestCache.remove(ns + old);
        }
    }

    private static <T> void refresh(RMapCache<String, T> cache, String key, T value) {
        cache.put(key, value, OpenAiCompatSessionPolicy.TTL_VALUE, OpenAiCompatSessionPolicy.TTL_UNIT);
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
        return extractText(parsed, OpenAiResponsesBodyPolicy.FIELD_MODEL);
    }

    private static String resolveCompatModel(AccountEntity account,
                                             JsonNode anthropic,
                                             String requestedModel,
                                             String responsesModel) {
        return OpenAiCompatModelPolicy.resolveAnthropicMessagesCompatModel(
                account == null ? null : account.getPlatform(),
                account == null ? null : account.getCredentials(),
                extractText(anthropic, AnthropicMessagesBodyPolicy.FIELD_MODEL),
                requestedModel,
                responsesModel);
    }

    private String resolveAnthropicCompatPromptCacheKey(AccountEntity account,
                                                       Long apiKeyId,
                                                       JsonNode anthropic,
                                                       String compatModel) {
        String promptCacheKey = CompatPromptCacheKeyPolicy.promptCacheKeyFromAnthropicMetadataSession(anthropic);
        if (promptCacheKey.isBlank()) {
            promptCacheKey = CompatPromptCacheKeyPolicy.deriveAnthropicCacheControlPromptCacheKey(anthropic);
        }
        if (promptCacheKey.isBlank()
                && CompatPromptCacheKeyPolicy.shouldAutoInjectPromptCacheKeyForCompat(compatModel)) {
            String digestChain = CompatPromptCacheKeyPolicy.buildOpenAICompatAnthropicDigestChain(anthropic);
            DigestMatch match = findAnthropicDigestPromptCacheKey(account, apiKeyId, digestChain);
            promptCacheKey = !match.promptCacheKey().isBlank()
                    ? match.promptCacheKey()
                    : CompatPromptCacheKeyPolicy.promptCacheKeyFromAnthropicDigest(digestChain);
        }
        return promptCacheKey;
    }

    private static int messageCount(JsonNode anthropic) {
        JsonNode messages = anthropic == null ? null : anthropic.get(AnthropicMessagesBodyPolicy.FIELD_MESSAGES);
        return messages != null && messages.isArray() ? messages.size() : 0;
    }

    private static String extractText(JsonNode root, String field) {
        if (root == null || root.isMissingNode() || field == null) return "";
        JsonNode value = root.get(field);
        return value != null && value.isTextual() ? value.asText().trim() : "";
    }

    private static String sessionKey(AccountEntity account, Long apiKeyId, String promptCacheKey) {
        return OpenAiCompatSessionPolicy.sessionKey(accountId(account), apiKeyId, promptCacheKey);
    }

    private static String digestNamespace(AccountEntity account, Long apiKeyId) {
        return OpenAiCompatSessionPolicy.digestNamespace(accountId(account), apiKeyId);
    }

    private static Long accountId(AccountEntity account) {
        return account == null ? null : account.getId();
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

    public record FullReplayTrimState(String body, boolean trimmed, int messagesAfterTrim) {
    }

    public record DigestMatch(String promptCacheKey, String matchedDigestChain) {
        static DigestMatch empty() {
            return new DigestMatch("", "");
        }
    }
}
