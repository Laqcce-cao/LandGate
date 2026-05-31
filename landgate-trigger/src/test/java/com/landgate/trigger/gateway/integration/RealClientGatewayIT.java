package com.landgate.trigger.gateway.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 真实客户端集成测试。
 * <p>
 * 仿照 Anthropic Claude CLI / OpenAI Codex CLI 的请求头与 body，
 * 通过 LandGate 网关访问已配置的上游账号，验证：
 * <ul>
 *   <li>API Key 鉴权正常</li>
 *   <li>路由分发正确（Anthropic → /v1/messages, OpenAI → /v1/chat/completions）</li>
 *   <li>上游响应能正常解析</li>
 *   <li>OAuth 伪装请求头不会破坏纯 API Key 链路</li>
 * </ul>
 * <p>
 * 数据来源（sub2api MySQL，通过 Phase 9 调研获取）：
 * <ul>
 *   <li>测试 API Key (id=5, name=test): ak-e7444d773d1cf3a49f8f22b2994cad6cdd6f2868aa879841e8daa8bc03d06a55</li>
 *   <li>Anthropic 上游账号 (id=3, deepseek): https://api.deepseek.com/anthropic, model=deepseek-v4-pro</li>
 *   <li>OpenAI 上游账号 (id=12, sg): http://47.108.75.75:58309, model=gpt-5.5</li>
 * </ul>
 * <p>
 * 网关默认地址：http://localhost:8080，可通过系统属性 -Dlandgate.gateway.url 覆盖。
 * <p>
 * 默认禁用：需要本地启动 LandGate (mvn spring-boot:run -Dspring-boot.run.profiles=test)，
 * 然后用 -Dlandgate.it.enabled=true 启用本测试。
 */
@DisplayName("真实客户端集成测试 - Anthropic CLI / Codex CLI")
@EnabledIfSystemProperty(named = "landgate.it.enabled", matches = "true")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RealClientGatewayIT {

    private static final String GATEWAY_URL = System.getProperty(
            "landgate.gateway.url", "http://localhost:8080");

    /** 来自数据库 api_keys.id=5 (name=test) */
    private static final String API_KEY = System.getProperty(
            "landgate.test.api_key",
            "ak-e7444d773d1cf3a49f8f22b2994cad6cdd6f2868aa879841e8daa8bc03d06a55");

    /** 来自数据库 accounts.id=3 supported_models */
    private static final String ANTHROPIC_MODEL = System.getProperty(
            "landgate.test.anthropic_model", "deepseek-v4-pro");

    /** 来自数据库 accounts.id=12 supported_models */
    private static final String OPENAI_MODEL = System.getProperty(
            "landgate.test.openai_model", "gpt-5.5");

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    // ============================================================
    // Anthropic Claude CLI 模拟
    // ============================================================

    @Test
    @Order(3)
    @DisplayName("Claude CLI: /v1/messages 非流式")
    void claudeCliMessages() throws Exception {
        String body = """
                {
                  "model": "%s",
                  "max_tokens": 64,
                  "messages": [
                    {"role": "user", "content": "Reply with exactly: OK"}
                  ]
                }
                """.formatted(ANTHROPIC_MODEL);

        HttpRequest req = anthropicCliRequest("/v1/messages", body);
        HttpResponse<String> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());

        System.out.println("[Claude CLI] status=" + resp.statusCode());
        System.out.println("[Claude CLI] body=" + truncate(resp.body(), 500));

        assertEquals(200, resp.statusCode(),
                "Anthropic 上游应返回 200，实际响应：" + resp.body());
        JsonNode json = JSON.readTree(resp.body());
        assertEquals("message", json.path("type").asText());
        assertTrue(json.path("content").isArray() && json.path("content").size() > 0,
                "应返回至少一个 content block");
    }

    @Test
    @Order(4)
    @DisplayName("Claude CLI: /v1/messages 流式 SSE")
    void claudeCliMessagesStream() throws Exception {
        String body = """
                {
                  "model": "%s",
                  "max_tokens": 1024,
                  "stream": true,
                  "messages": [
                    {"role": "user", "content": "Say hi"}
                  ]
                }
                """.formatted(ANTHROPIC_MODEL);

        HttpRequest req = anthropicCliRequest("/v1/messages", body);
        HttpResponse<String> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());

        System.out.println("[Claude CLI Stream] status=" + resp.statusCode());
        System.out.println("[Claude CLI Stream] body=" + resp.body());

        assertEquals(200, resp.statusCode());
        // Anthropic SSE 必有的事件
        assertTrue(resp.body().contains("event: message_start"),
                "缺少 message_start 事件");
        assertTrue(resp.body().contains("event: message_stop")
                        || resp.body().contains("event: message_delta"),
                "缺少 message_stop 或 message_delta 事件");
    }

    @Test
    @Order(5)
    @DisplayName("Claude CLI: /v1/messages/count_tokens")
    void claudeCliCountTokens() throws Exception {
        String body = """
                {
                  "model": "%s",
                  "messages": [{"role":"user","content":"Hello world"}]
                }
                """.formatted(ANTHROPIC_MODEL);

        HttpRequest req = anthropicCliRequest("/v1/messages/count_tokens", body);
        HttpResponse<String> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());

        System.out.println("[Claude CLI count_tokens] status=" + resp.statusCode()
                + " body=" + truncate(resp.body(), 200));
        // 上游可能不支持，4xx 也可接受，只要不是 5xx
        assertTrue(resp.statusCode() < 500, "网关不应返回 5xx");
    }

    // ============================================================
    // OpenAI Codex CLI 模拟
    // ============================================================

    @Test
    @Order(6)
    @DisplayName("Codex CLI: /v1/chat/completions 非流式")
    void codexCliChatCompletions() throws Exception {
        String body = """
                {
                  "model": "%s",
                  "max_tokens": 64,
                  "messages": [
                    {"role": "user", "content": "Reply with exactly: OK"}
                  ]
                }
                """.formatted(OPENAI_MODEL);

        HttpRequest req = codexCliRequest("/v1/chat/completions", body);
        HttpResponse<String> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());

        System.out.println("[Codex CLI] status=" + resp.statusCode());
        System.out.println("[Codex CLI] body=" + truncate(resp.body(), 500));

        assertEquals(200, resp.statusCode(),
                "OpenAI 上游应返回 200，实际响应：" + resp.body());
        JsonNode json = JSON.readTree(resp.body());
        assertEquals("chat.completion", json.path("object").asText());
        assertTrue(json.path("choices").isArray() && json.path("choices").size() > 0);
    }

    @Test
    @Order(7)
    @DisplayName("Codex CLI: /v1/chat/completions 流式 SSE")
    void codexCliChatCompletionsStream() throws Exception {
        String body = """
                {
                  "model": "%s",
                  "max_tokens": 32,
                  "stream": true,
                  "messages": [
                    {"role": "user", "content": "Say hi"}
                  ]
                }
                """.formatted(OPENAI_MODEL);

        HttpRequest req = codexCliRequest("/v1/chat/completions", body);
        HttpResponse<String> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());

        System.out.println("[Codex CLI Stream] status=" + resp.statusCode());
        System.out.println("[Codex CLI Stream] body=" + truncate(resp.body(), 500));

        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("data: ") && resp.body().contains("[DONE]"),
                "OpenAI SSE 应以 data: 开头并以 [DONE] 结束");
    }

    @Test
    @Order(8)
    @DisplayName("Codex CLI: /v1/responses（OpenAI Responses API）")
    void codexCliResponses() throws Exception {
        String body = """
                {
                  "model": "%s",
                  "input": [{"role":"user","content":"Reply OK"}],
                  "max_output_tokens": 64
                }
                """.formatted(OPENAI_MODEL);

        HttpRequest req = codexCliRequest("/v1/responses", body);
        HttpResponse<String> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());

        System.out.println("[Codex CLI /v1/responses] status=" + resp.statusCode()
                + " body=" + truncate(resp.body(), 500));
        // 部分上游不支持 /v1/responses，返回 4xx 可接受
        assertTrue(resp.statusCode() < 500,
                "网关不应返回 5xx，实际响应：" + resp.body());
    }

    // ============================================================
    // 鉴权失败用例
    // ============================================================

    @Test
    @Order(1)
    @DisplayName("缺少 API Key → 401")
    void missingApiKey() throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(GATEWAY_URL + "/v1/messages"))
                .header("Content-Type", "application/json")
                .header("anthropic-version", "2023-06-01")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"model\":\"" + ANTHROPIC_MODEL
                                + "\",\"max_tokens\":8,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"))
                .build();
        HttpResponse<String> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(401, resp.statusCode(), "无 API Key 应被拒");
    }

    @Test
    @Order(2)
    @DisplayName("错误的 API Key → 401")
    void invalidApiKey() throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(GATEWAY_URL + "/v1/messages"))
                .header("Content-Type", "application/json")
                .header("x-api-key", "ak-invalid-test-key")
                .header("anthropic-version", "2023-06-01")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"model\":\"" + ANTHROPIC_MODEL
                                + "\",\"max_tokens\":8,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"))
                .build();
        HttpResponse<String> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(401, resp.statusCode());
    }

    // ============================================================
    // 辅助方法：构造仿真请求
    // ============================================================

    /**
     * 仿造 Anthropic Claude CLI v2.1.92 的请求头。
     * 参考：landgate-trigger/.../OAuthMimicryService.java#L313
     */
    private HttpRequest anthropicCliRequest(String path, String jsonBody) {
        return HttpRequest.newBuilder(URI.create(GATEWAY_URL + path))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("x-api-key", API_KEY)
                .header("anthropic-version", "2023-06-01")
                .header("anthropic-beta", "messages-2023-12-15")
                .header("User-Agent", "claude-cli/2.1.92 (external, cli)")
                .header("X-Stainless-Lang", "js")
                .header("X-Stainless-Package-Version", "0.70.0")
                .header("X-App", "cli")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
    }

    /**
     * 仿造 OpenAI Codex CLI (codex_cli_rs) 的请求头。
     * 参考 sub2api openai_gateway_compact_log_test.go：codex_cli_rs/0.125.0
     */
    private HttpRequest codexCliRequest(String path, String jsonBody) {
        return HttpRequest.newBuilder(URI.create(GATEWAY_URL + path))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + API_KEY)
                .header("User-Agent", "codex_cli_rs/0.125.0")
                .header("originator", "codex_cli_rs")
                .header("openai-beta", "responses=v1")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...(truncated, total=" + s.length() + ")";
    }
}
