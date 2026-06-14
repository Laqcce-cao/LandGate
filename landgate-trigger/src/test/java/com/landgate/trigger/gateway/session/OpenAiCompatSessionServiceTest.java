package com.landgate.trigger.gateway.session;

import com.landgate.domain.account.model.entity.AccountEntity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.landgate.types.enums.AccountType;
import com.landgate.types.enums.Platform;
import com.landgate.types.gateway.OpenAiAnthropicMessagesCompatPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RMapCache;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("OpenAI compat session service 测试")
class OpenAiCompatSessionServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @DisplayName("Anthropic digest replay 增长后复用已绑定 prompt_cache_key")
    void digestPrefixMatchReusesPromptCacheKey() {
        OpenAiCompatSessionService service = newService();
        AccountEntity account = AccountEntity.builder()
                .id(6L)
                .platform(Platform.OPENAI)
                .type(AccountType.API_KEY)
                .build();

        String firstAnthropic = """
                {
                  "model":"gpt-5.5",
                  "system":"You are helpful.",
                  "messages":[{"role":"user","content":"Open repo"}]
                }""";
        String secondAnthropic = """
                {
                  "model":"gpt-5.5",
                  "system":"You are helpful.",
                  "messages":[
                    {"role":"user","content":"Open repo"},
                    {"role":"assistant","content":"Opened."},
                    {"role":"user","content":"Run tests"}
                  ]
                }""";
        String responsesBody = """
                {"model":"gpt-5.5","input":[{"type":"message","role":"user","content":"Open repo"}]}""";

        OpenAiCompatSessionService.CompatState first = service.prepareAnthropicMessagesCompat(
                account, 42L, firstAnthropic, responsesBody, "gpt-5.5");
        assertTrue(first.promptCacheKey().startsWith("anthropic-digest-"));
        service.bindAnthropicDigestPromptCacheKey(
                account, 42L, first.digestChain(), first.promptCacheKey(), first.matchedDigestChain());

        OpenAiCompatSessionService.CompatState second = service.prepareAnthropicMessagesCompat(
                account, 42L, secondAnthropic, responsesBody, "gpt-5.5");

        assertEquals(first.promptCacheKey(), second.promptCacheKey());
        assertEquals(first.digestChain(), second.matchedDigestChain());
    }

    @Test
    @DisplayName("OpenAI messages compat Responses body 缺 instructions 时补空字符串")
    void messagesCompatEnsuresInstructionsField() throws Exception {
        OpenAiCompatSessionService service = newService();
        AccountEntity account = AccountEntity.builder()
                .id(6L)
                .platform(Platform.OPENAI)
                .type(AccountType.API_KEY)
                .build();
        String anthropic = """
                {"model":"gpt-5.5","messages":[{"role":"user","content":"你好"}]}""";
        String responsesBody = """
                {"model":"gpt-5.5","input":[{"type":"message","role":"user","content":"你好"}]}""";

        OpenAiCompatSessionService.CompatState state = service.prepareAnthropicMessagesCompat(
                account, 42L, anthropic, responsesBody, "gpt-5.5");
        JsonNode root = JSON.readTree(state.body());

        assertTrue(root.has("instructions"));
        assertEquals("", root.get("instructions").asText());
        assertTrue(root.get("input").get(0).get("content").get(0).get("text").asText()
                .contains(OpenAiAnthropicMessagesCompatPolicy.TODO_GUARD_MARKER));
        assertEquals("你好", root.get("input").get(1).get("content").asText());
    }

    @Test
    @DisplayName("OpenAI API Key messages compat 删除 max_output_tokens")
    void apiKeyMessagesCompatRemovesUnsupportedMaxOutputTokens() throws Exception {
        OpenAiCompatSessionService service = newService();
        AccountEntity account = AccountEntity.builder()
                .id(6L)
                .platform(Platform.OPENAI)
                .type(AccountType.API_KEY)
                .build();
        String anthropic = """
                {"model":"gpt-5.5","max_tokens":1024,"messages":[{"role":"user","content":"你好"}]}""";
        String responsesBody = """
                {
                  "model":"gpt-5.5",
                  "max_output_tokens":1024,
                  "max_completion_tokens":1024,
                  "input":[{"type":"message","role":"user","content":"你好"}]
                }""";

        OpenAiCompatSessionService.CompatState state = service.prepareAnthropicMessagesCompat(
                account, 42L, anthropic, responsesBody, "gpt-5.5");
        JsonNode root = JSON.readTree(state.body());

        assertFalse(root.has("max_output_tokens"));
        assertFalse(root.has("max_completion_tokens"));
        assertTrue(root.has("instructions"));
    }

    @Test
    @DisplayName("OpenAI OAuth messages compat 不在 session 层删除 max_output_tokens")
    void oauthMessagesCompatKeepsMaxOutputTokensForTransformerNormalization() throws Exception {
        OpenAiCompatSessionService service = newService();
        AccountEntity account = AccountEntity.builder()
                .id(6L)
                .platform(Platform.OPENAI)
                .type(AccountType.OAUTH)
                .build();
        String anthropic = """
                {"model":"gpt-5.5","max_tokens":1024,"messages":[{"role":"user","content":"你好"}]}""";
        String responsesBody = """
                {
                  "model":"gpt-5.5",
                  "max_output_tokens":1024,
                  "input":[{"type":"message","role":"user","content":"你好"}]
                }""";

        OpenAiCompatSessionService.CompatState state = service.prepareAnthropicMessagesCompat(
                account, 42L, anthropic, responsesBody, "gpt-5.5");
        JsonNode root = JSON.readTree(state.body());

        assertEquals(1024, root.get("max_output_tokens").asInt());
    }

    @Test
    @DisplayName("OpenAI API Key messages compat 为 Codex 模型插入 Claude Code todo guard")
    void apiKeyMessagesCompatAddsTodoGuardForCodexModel() throws Exception {
        OpenAiCompatSessionService service = newService();
        AccountEntity account = AccountEntity.builder()
                .id(6L)
                .platform(Platform.OPENAI)
                .type(AccountType.API_KEY)
                .build();
        String anthropic = """
                {"model":"gpt-5.5","messages":[{"role":"user","content":"你好"}]}""";
        String responsesBody = """
                {"model":"gpt-5.5","input":[{"type":"message","role":"user","content":"你好"}]}""";

        OpenAiCompatSessionService.CompatState state = service.prepareAnthropicMessagesCompat(
                account, 42L, anthropic, responsesBody, "gpt-5.5");
        JsonNode input = JSON.readTree(state.body()).get("input");

        assertEquals("developer", input.get(0).get("role").asText());
        assertTrue(input.get(0).get("content").get(0).get("text").asText()
                .contains(OpenAiAnthropicMessagesCompatPolicy.TODO_GUARD_MARKER));
        assertEquals("user", input.get(1).get("role").asText());
    }

    @Test
    @DisplayName("OpenAI API Key messages compat 对映射后的 Codex 模型执行 full replay trim")
    void apiKeyCompatTrimsFullReplayForMappedCodexModel() throws Exception {
        OpenAiCompatSessionService service = newService();
        AccountEntity account = AccountEntity.builder()
                .id(6L)
                .platform(Platform.OPENAI)
                .type(AccountType.API_KEY)
                .credentials("""
                        {"model_mapping":{"claude-sonnet-4-5":"gpt-5.3-codex"}}""")
                .build();

        OpenAiCompatSessionService.FullReplayTrimState state =
                service.trimAnthropicMessagesFullReplayForApiKeyCompat(
                        account, 42L, requestWithMessages("claude-sonnet-4-5", 15), "claude-sonnet-4-5");
        JsonNode root = JSON.readTree(state.body());

        assertTrue(state.trimmed());
        assertEquals(12, state.messagesAfterTrim());
        assertEquals("message-03", root.get("messages").get(0).get("content").asText());
        assertEquals("message-14", root.get("messages").get(11).get("content").asText());
    }

    @Test
    @DisplayName("OpenAI API Key messages compat 对非 Codex 模型不执行 full replay trim")
    void apiKeyCompatSkipsFullReplayTrimForNonCodexModel() throws Exception {
        OpenAiCompatSessionService service = newService();
        AccountEntity account = AccountEntity.builder()
                .id(6L)
                .platform(Platform.OPENAI)
                .type(AccountType.API_KEY)
                .credentials("{}")
                .build();

        OpenAiCompatSessionService.FullReplayTrimState state =
                service.trimAnthropicMessagesFullReplayForApiKeyCompat(
                        account, 42L, requestWithMessages("gpt-4o", 15), "gpt-4o");
        JsonNode root = JSON.readTree(state.body());

        assertFalse(state.trimmed());
        assertEquals(15, root.get("messages").size());
        assertEquals("message-00", root.get("messages").get(0).get("content").asText());
    }

    @Test
    @DisplayName("OpenAI OAuth messages compat 保留 full replay 以便上游缓存增长")
    void oauthCompatKeepsFullReplayForCacheGrowth() throws Exception {
        OpenAiCompatSessionService service = newService();
        AccountEntity account = AccountEntity.builder()
                .id(6L)
                .platform(Platform.OPENAI)
                .type(AccountType.OAUTH)
                .credentials("""
                        {"model_mapping":{"claude-sonnet-4-5":"gpt-5.4"}}""")
                .build();

        OpenAiCompatSessionService.FullReplayTrimState state =
                service.trimAnthropicMessagesFullReplayForApiKeyCompat(
                        account, 42L, requestWithMessages("claude-sonnet-4-5", 15), "claude-sonnet-4-5");
        JsonNode root = JSON.readTree(state.body());

        assertFalse(state.trimmed());
        assertEquals(15, root.get("messages").size());
        assertEquals("message-00", root.get("messages").get(0).get("content").asText());
    }

    @SuppressWarnings("unchecked")
    private static OpenAiCompatSessionService newService() {
        RMapCache<String, String> responseIds = mapCache(new HashMap<>());
        RMapCache<String, String> turnStates = mapCache(new HashMap<>());
        RMapCache<String, Boolean> disabled = mapCache(new HashMap<>());
        RMapCache<String, String> digests = mapCache(new HashMap<>());
        return new OpenAiCompatSessionService(responseIds, turnStates, disabled, digests);
    }

    private static String requestWithMessages(String model, int count) {
        StringBuilder messages = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) messages.append(',');
            messages.append("""
                    {"role":"user","content":"message-%02d"}""".formatted(i));
        }
        return """
                {"model":"%s","messages":[%s]}""".formatted(model, messages);
    }

    private static <V> RMapCache<String, V> mapCache(Map<String, V> backing) {
        return (RMapCache<String, V>) Proxy.newProxyInstance(
                OpenAiCompatSessionServiceTest.class.getClassLoader(),
                new Class<?>[]{RMapCache.class},
                (proxy, method, args) -> {
                    return switch (method.getName()) {
                        case "get" -> backing.get((String) args[0]);
                        case "put" -> {
                            backing.put((String) args[0], (V) args[1]);
                            yield null;
                        }
                        case "remove" -> backing.remove((String) args[0]);
                        case "isEmpty" -> backing.isEmpty();
                        case "size" -> backing.size();
                        case "clear" -> {
                            backing.clear();
                            yield null;
                        }
                        default -> defaultValue(method.getReturnType());
                    };
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == double.class) return 0D;
        if (type == float.class) return 0F;
        return null;
    }
}
