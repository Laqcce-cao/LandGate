package com.landgate.trigger.gateway;

import com.landgate.trigger.gateway.route.EndpointKind;
import com.landgate.trigger.gateway.route.UpstreamRoute;
import com.landgate.types.enums.Platform;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("GatewayProtocolPlanner 协议计划测试")
class GatewayProtocolPlannerTest {

    private final GatewayProtocolPlanner planner = new GatewayProtocolPlanner();

    @Test
    @DisplayName("客户端协议和上游协议不同且非透传时需要翻译")
    void differentFormatsRequireTranslation() {
        GatewayProtocolPlan plan = planner.plan(Platform.OPENAI, route("chat_completions", "responses"));

        assertEquals("chat_completions", plan.clientFormat());
        assertEquals("responses", plan.upstreamFormat());
        assertFalse(plan.passthrough());
        assertTrue(plan.translationRequired());
        assertEquals("translated", plan.prepareRequestBody("req", "body",
                (body, clientFormat, upstreamFormat) -> "translated"));
    }

    @Test
    @DisplayName("客户端协议和上游协议相同时透传")
    void sameFormatPassthroughSkipsTranslation() {
        GatewayProtocolPlan plan = planner.plan(Platform.OPENAI, route("responses", "responses"));

        assertTrue(plan.passthrough());
        assertFalse(plan.translationRequired());
        assertEquals("body", plan.prepareRequestBody("req", "body",
                (body, clientFormat, upstreamFormat) -> "translated"));
    }

    @Test
    @DisplayName("缺少 clientFormat 时回退入口 platform 格式")
    void missingClientFormatFallsBackToRequestPlatform() {
        GatewayProtocolPlan plan = planner.plan(Platform.ANTHROPIC, route(null, "responses"));

        assertEquals("messages", plan.clientFormat());
        assertEquals("responses", plan.upstreamFormat());
        assertTrue(plan.translationRequired());
    }

    private static UpstreamRoute route(String clientFormat, String upstreamFormat) {
        return new UpstreamRoute(
                Platform.OPENAI,
                clientFormat,
                upstreamFormat,
                EndpointKind.OPENAI_RESPONSES,
                "https://api.openai.com/v1/responses",
                false,
                false,
                upstreamFormat,
                "test"
        );
    }
}
