package com.landgate.trigger.gateway;

import com.landgate.trigger.gateway.handler.AnthropicGatewayHandler;
import com.landgate.trigger.gateway.handler.GeminiGatewayHandler;
import com.landgate.trigger.gateway.handler.OpenAiGatewayHandler;
import com.landgate.types.enums.Platform;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

/**
 * 网关处理器工厂 —— 根据平台 {@link Platform} 返回对应的 {@link IGatewayHandler}。
 * <p>
 * Antigravity 平台复用 Anthropic 处理器（Antigravity 协议与 Anthropic 兼容）。
 */
@Component
@RequiredArgsConstructor
public class GatewayHandlerFactory {

    private final AnthropicGatewayHandler anthropicHandler;
    private final OpenAiGatewayHandler openAiHandler;
    private final GeminiGatewayHandler geminiHandler;

    private final Map<Platform, IGatewayHandler> registry = new EnumMap<>(Platform.class);

    @PostConstruct
    void init() {
        registry.put(Platform.ANTHROPIC, anthropicHandler);
        registry.put(Platform.OPENAI, openAiHandler);
        registry.put(Platform.GEMINI, geminiHandler);
        registry.put(Platform.ANTIGRAVITY, anthropicHandler);
    }

    public IGatewayHandler getHandler(Platform platform) {
        IGatewayHandler handler = registry.get(platform);
        if (handler == null) {
            throw new IllegalArgumentException("No gateway handler for platform: " + platform);
        }
        return handler;
    }
}
