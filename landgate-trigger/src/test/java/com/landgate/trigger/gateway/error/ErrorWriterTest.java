package com.landgate.trigger.gateway.error;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("协议错误 envelope 测试")
class ErrorWriterTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @DisplayName("OpenAI 错误使用官方 error 对象 envelope")
    void openAiErrorEnvelope() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        new OpenAiErrorWriter().writeError(
                response, 401, "authentication_error", "Invalid API key\ncheck credentials");

        JsonNode body = JSON.readTree(response.getContentAsString());
        JsonNode error = body.get("error");

        assertEquals(401, response.getStatus());
        assertTrue(response.getContentType().startsWith("application/json"));
        assertEquals("Invalid API key\ncheck credentials", error.get("message").asText());
        assertEquals("authentication_error", error.get("type").asText());
        assertTrue(error.get("param").isNull());
        assertTrue(error.get("code").isNull());
    }

    @Test
    @DisplayName("Anthropic 错误使用 type=error envelope")
    void anthropicErrorEnvelope() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        new AnthropicErrorWriter().writeError(
                response, 429, "rate_limit_error", "Too many requests\ttry later");

        JsonNode body = JSON.readTree(response.getContentAsString());

        assertEquals(429, response.getStatus());
        assertTrue(response.getContentType().startsWith("application/json"));
        assertEquals("error", body.get("type").asText());
        assertEquals("rate_limit_error", body.get("error").get("type").asText());
        assertEquals("Too many requests\ttry later", body.get("error").get("message").asText());
    }
}
