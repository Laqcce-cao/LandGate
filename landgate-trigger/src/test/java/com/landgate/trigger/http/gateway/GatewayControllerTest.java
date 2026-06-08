package com.landgate.trigger.http.gateway;

import com.landgate.infrastructure.dao.IUserDao;
import com.landgate.trigger.gateway.GatewayDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/**
 * GatewayController 单元测试 —— 验证 Codex/Responses 兼容入口路由会进入统一网关派发器。
 */
@DisplayName("GatewayController 路由兼容测试")
class GatewayControllerTest {

    private final GatewayDispatcher dispatcher = mock(GatewayDispatcher.class);
    private final IUserDao userDao = mock(IUserDao.class);
    private final MockMvc mockMvc = standaloneSetup(new GatewayController(dispatcher, userDao)).build();

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
}
