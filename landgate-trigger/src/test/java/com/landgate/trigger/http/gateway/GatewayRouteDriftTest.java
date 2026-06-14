package com.landgate.trigger.http.gateway;

import com.landgate.types.gateway.GatewayClientRoute;
import com.landgate.types.gateway.GatewayPathPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Gateway route drift 测试")
class GatewayRouteDriftTest {

    @Test
    @DisplayName("三协议核心客户端路由在 Controller、Dispatcher route facts、PathPolicy 中保持一致")
    void coreClientRoutesStayAlignedAcrossGatewayLayers() {
        Set<String> controllerPostPaths = controllerPostPaths();

        for (GatewayClientRoute route : GatewayClientRoute.values()) {
            String path = route.pathPrefix();

            assertTrue(controllerPostPaths.contains(path),
                    () -> "GatewayController missing @PostMapping for " + path);
            assertEquals(route, GatewayClientRoute.resolve(path).orElseThrow(),
                    () -> "GatewayClientRoute cannot resolve " + path);
            assertTrue(GatewayPathPolicy.isGatewayPath(path),
                    () -> "GatewayPathPolicy does not cover " + path);
        }
    }

    @Test
    @DisplayName("Responses 子路径入口保持 Controller 覆盖且 Dispatcher 仍解析为 Responses")
    void responsesSubpathsStayAlignedAcrossGatewayLayers() {
        Set<String> controllerPostPaths = controllerPostPaths();

        assertTrue(controllerPostPaths.contains("/v1/responses/**"));
        assertTrue(controllerPostPaths.contains("/responses/**"));
        assertTrue(controllerPostPaths.contains("/backend-api/codex/responses/**"));

        assertEquals(GatewayClientRoute.V1_RESPONSES,
                GatewayClientRoute.resolve("/v1/responses/compact").orElseThrow());
        assertEquals(GatewayClientRoute.RESPONSES_ALIAS,
                GatewayClientRoute.resolve("/responses/compact").orElseThrow());
        assertEquals(GatewayClientRoute.CODEX_RESPONSES,
                GatewayClientRoute.resolve("/backend-api/codex/responses/compact").orElseThrow());

        assertTrue(GatewayPathPolicy.isGatewayPath("/v1/responses/compact"));
        assertTrue(GatewayPathPolicy.isGatewayPath("/responses/compact"));
        assertTrue(GatewayPathPolicy.isGatewayPath("/backend-api/codex/responses/compact"));
    }

    private static Set<String> controllerPostPaths() {
        return Arrays.stream(GatewayController.class.getDeclaredMethods())
                .map(GatewayRouteDriftTest::postMapping)
                .flatMap(Arrays::stream)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static String[] postMapping(Method method) {
        PostMapping mapping = method.getAnnotation(PostMapping.class);
        if (mapping == null) {
            return new String[0];
        }
        String[] values = mapping.value();
        return values.length > 0 ? values : mapping.path();
    }
}
