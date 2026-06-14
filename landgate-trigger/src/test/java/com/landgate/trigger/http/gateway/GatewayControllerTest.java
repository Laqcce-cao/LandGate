package com.landgate.trigger.http.gateway;

import com.landgate.infrastructure.dao.IUserDao;
import com.landgate.infrastructure.dao.po.ApiKeyPO;
import com.landgate.infrastructure.dao.po.UserPO;
import com.landgate.trigger.gateway.GatewayDispatcher;
import com.landgate.trigger.gateway.counttokens.CountTokensGatewayService;
import com.landgate.types.gateway.GatewayUnsupportedFeaturePolicy;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/**
 * GatewayController 单元测试 —— 验证 Codex/Responses 兼容入口路由会进入统一网关派发器。
 */
@DisplayName("GatewayController 路由兼容测试")
class GatewayControllerTest {

    private final GatewayDispatcher dispatcher = mock(GatewayDispatcher.class);
    private final IUserDao userDao = mock(IUserDao.class);
    private final CountTokensGatewayService countTokensGatewayService = mock(CountTokensGatewayService.class);
    private final MockMvc mockMvc = standaloneSetup(
            new GatewayController(dispatcher, userDao, countTokensGatewayService)).build();

    @Test
    @DisplayName("Codex 直连 Responses 路径进入 dispatcher")
    void backendCodexResponsesRoutesToDispatcher() throws Exception {
        String body = "{\"model\":\"gpt-5.5\",\"input\":\"hi\"}";

        mockMvc.perform(post("/backend-api/codex/responses")
                        .requestAttr("group_id", 1L)
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk());

        verify(dispatcher).dispatch(any(HttpServletRequest.class), any(HttpServletResponse.class), eq(body));
    }

    @Test
    @DisplayName("OpenAI Responses 简短路径进入 dispatcher")
    void bareResponsesRoutesToDispatcher() throws Exception {
        String body = "{\"model\":\"gpt-5.5\",\"input\":\"hi\"}";

        mockMvc.perform(post("/responses")
                        .requestAttr("group_id", 1L)
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk());

        verify(dispatcher).dispatch(any(HttpServletRequest.class), any(HttpServletResponse.class), eq(body));
    }

    @Test
    @DisplayName("剩余用量查询仅保留 /v1/usage 路径")
    void usageRoutesOnlyUnderV1() throws Exception {
        ApiKeyPO apiKey = ApiKeyPO.builder()
                .id(1L)
                .userId(2L)
                .key("sk-test")
                .quota(BigDecimal.ZERO)
                .build();
        UserPO user = UserPO.builder()
                .id(2L)
                .balance(new BigDecimal("12.34"))
                .build();
        when(userDao.selectById(2L)).thenReturn(user);

        mockMvc.perform(get("/v1/usage")
                        .requestAttr("api_key", apiKey)
                        .requestAttr("user_id", 2L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.remaining").value(12.34))
                .andExpect(jsonPath("$.unit").value("USD"));

        mockMvc.perform(get("/usage"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("count_tokens 路径进入独立服务")
    void countTokensRoutesToCountTokensService() throws Exception {
        String body = "{\"model\":\"claude-sonnet-4-5\",\"messages\":[]}";

        mockMvc.perform(post("/v1/messages/count_tokens")
                        .contentType("application/json")
                .content(body))
                .andExpect(status().isOk());

        verify(countTokensGatewayService).handle(
                eq(body), any(HttpServletRequest.class), any(HttpServletResponse.class));
    }

    @Test
    @DisplayName("Gemini 当前显式返回 unsupported")
    void geminiIsExplicitlyUnsupported() throws Exception {
        mockMvc.perform(post("/v1beta/models/gemini-2.5-flash:generateContent")
                        .contentType("application/json")
                .content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.status").value(GatewayUnsupportedFeaturePolicy.GOOGLE_STATUS_UNSUPPORTED))
                .andExpect(jsonPath("$.error.message").value(GatewayUnsupportedFeaturePolicy.GEMINI_UNSUPPORTED_MESSAGE));
    }
}
