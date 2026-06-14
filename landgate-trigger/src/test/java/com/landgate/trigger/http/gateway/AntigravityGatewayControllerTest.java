package com.landgate.trigger.http.gateway;

import com.landgate.types.gateway.GatewayUnsupportedFeaturePolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

@DisplayName("AntigravityGatewayController unsupported 路由测试")
class AntigravityGatewayControllerTest {

    private final MockMvc mockMvc = standaloneSetup(new AntigravityGatewayController()).build();

    @Test
    @DisplayName("Antigravity Messages 当前显式返回 unsupported")
    void antigravityMessagesIsExplicitlyUnsupported() throws Exception {
        mockMvc.perform(post("/antigravity/v1/messages")
                .contentType("application/json")
                .content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.type").value(GatewayUnsupportedFeaturePolicy.ERROR_TYPE_UNSUPPORTED))
                .andExpect(jsonPath("$.error.message").value(
                        GatewayUnsupportedFeaturePolicy.ANTIGRAVITY_UNSUPPORTED_MESSAGE));
    }

    @Test
    @DisplayName("Antigravity Chat 当前显式返回 unsupported")
    void antigravityChatIsExplicitlyUnsupported() throws Exception {
        mockMvc.perform(post("/antigravity/v1/chat/completions")
                .contentType("application/json")
                .content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.type").value(GatewayUnsupportedFeaturePolicy.ERROR_TYPE_UNSUPPORTED))
                .andExpect(jsonPath("$.error.message").value(
                        GatewayUnsupportedFeaturePolicy.ANTIGRAVITY_UNSUPPORTED_MESSAGE));
    }

    @Test
    @DisplayName("Antigravity Gemini 当前显式返回 unsupported")
    void antigravityGeminiIsExplicitlyUnsupported() throws Exception {
        mockMvc.perform(post("/antigravity/v1beta/models/gemini-2.5-flash:generateContent")
                .contentType("application/json")
                .content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.type").value(GatewayUnsupportedFeaturePolicy.ERROR_TYPE_UNSUPPORTED))
                .andExpect(jsonPath("$.error.message").value(
                        GatewayUnsupportedFeaturePolicy.ANTIGRAVITY_UNSUPPORTED_MESSAGE));
    }
}
