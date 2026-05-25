package com.landgate.trigger.gateway;

import com.landgate.trigger.gateway.error.AnthropicErrorWriter;
import com.landgate.trigger.gateway.error.GeminiErrorWriter;
import com.landgate.trigger.gateway.error.OpenAiErrorWriter;
import com.landgate.types.enums.Platform;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

/**
 * 平台路由器 —— 根据 {@link Platform} 返回对应的 Transformer、UsageParser、ErrorWriter。
 * <p>
 * 网关在选定账户后，通过账户的 platform 字段从本路由器获取对应的上游组件，
 * 从而向正确的上游平台构造请求、解析用量、返回错误。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlatformRouter {

    private final AnthropicTransformer anthropicTransformer;
    private final OpenAiTransformer openAiTransformer;
    private final GeminiTransformer geminiTransformer;

    private final UsageParser usageParser;
    private final OpenAiUsageParser openAiUsageParser;
    private final GeminiUsageParser geminiUsageParser;

    private final AnthropicErrorWriter anthropicErrorWriter;
    private final OpenAiErrorWriter openAiErrorWriter;
    private final GeminiErrorWriter geminiErrorWriter;

    private final Map<Platform, IRequestTransformer> transformerMap = new EnumMap<>(Platform.class);
    private final Map<Platform, IUsageParser> usageParserMap = new EnumMap<>(Platform.class);
    private final Map<Platform, IErrorWriter> errorWriterMap = new EnumMap<>(Platform.class);

    @PostConstruct
    void init() {
        transformerMap.put(Platform.ANTHROPIC, anthropicTransformer);
        transformerMap.put(Platform.ANTIGRAVITY, anthropicTransformer);
        transformerMap.put(Platform.OPENAI, openAiTransformer);
        transformerMap.put(Platform.GEMINI, geminiTransformer);

        usageParserMap.put(Platform.ANTHROPIC, usageParser);
        usageParserMap.put(Platform.ANTIGRAVITY, usageParser);
        usageParserMap.put(Platform.OPENAI, openAiUsageParser);
        usageParserMap.put(Platform.GEMINI, geminiUsageParser);

        errorWriterMap.put(Platform.ANTHROPIC, anthropicErrorWriter);
        errorWriterMap.put(Platform.ANTIGRAVITY, anthropicErrorWriter);
        errorWriterMap.put(Platform.OPENAI, openAiErrorWriter);
        errorWriterMap.put(Platform.GEMINI, geminiErrorWriter);

        log.info("PlatformRouter initialized with {} platforms", transformerMap.size());
    }

    /** 根据平台获取请求转换器 */
    public IRequestTransformer getTransformer(Platform platform) {
        IRequestTransformer t = transformerMap.get(platform);
        if (t == null) {
            throw new IllegalArgumentException("No IRequestTransformer for platform: " + platform);
        }
        return t;
    }

    /** 根据平台获取用量解析器 */
    public IUsageParser getUsageParser(Platform platform) {
        IUsageParser p = usageParserMap.get(platform);
        if (p == null) {
            throw new IllegalArgumentException("No IUsageParser for platform: " + platform);
        }
        return p;
    }

    /** 根据平台获取错误响应写入器 */
    public IErrorWriter getErrorWriter(Platform platform) {
        IErrorWriter w = errorWriterMap.get(platform);
        if (w == null) {
            throw new IllegalArgumentException("No IErrorWriter for platform: " + platform);
        }
        return w;
    }
}
