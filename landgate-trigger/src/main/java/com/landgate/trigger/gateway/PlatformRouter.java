package com.landgate.trigger.gateway;

import com.landgate.trigger.gateway.error.AnthropicErrorWriter;
import com.landgate.trigger.gateway.error.IErrorWriter;
import com.landgate.trigger.gateway.error.OpenAiErrorWriter;
import com.landgate.trigger.gateway.route.UpstreamRoute;
import com.landgate.trigger.gateway.transformer.AnthropicTransformer;
import com.landgate.trigger.gateway.transformer.IRequestTransformer;
import com.landgate.trigger.gateway.transformer.OpenAiTransformer;
import com.landgate.trigger.gateway.usage.IUsageParser;
import com.landgate.trigger.gateway.usage.OpenAiUsageParser;
import com.landgate.trigger.gateway.usage.ResponsesUsageParser;
import com.landgate.trigger.gateway.usage.AnthropicUsageParser;
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

    private final AnthropicUsageParser anthropicUsageParser;
    private final OpenAiUsageParser openAiUsageParser;
    private final ResponsesUsageParser responsesUsageParser;

    private final AnthropicErrorWriter anthropicErrorWriter;
    private final OpenAiErrorWriter openAiErrorWriter;

    private final Map<Platform, IRequestTransformer> transformerMap = new EnumMap<>(Platform.class);
    private final Map<Platform, IUsageParser> usageParserMap = new EnumMap<>(Platform.class);
    private final Map<Platform, IErrorWriter> errorWriterMap = new EnumMap<>(Platform.class);

    @PostConstruct
    void init() {
        transformerMap.put(Platform.ANTHROPIC, anthropicTransformer);
        transformerMap.put(Platform.OPENAI, openAiTransformer);

        // OpenAI 平台同时承载 chat_completions 和 responses 两种 usage schema；
        // 平台粗粒度默认使用 Chat Completions，正常网关路径优先通过 UpstreamRoute.usageFormat 精确选择。
        usageParserMap.put(Platform.ANTHROPIC, anthropicUsageParser);
        usageParserMap.put(Platform.OPENAI, openAiUsageParser);

        errorWriterMap.put(Platform.ANTHROPIC, anthropicErrorWriter);
        errorWriterMap.put(Platform.OPENAI, openAiErrorWriter);

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

    /**
     * 根据平台 + 上游 formatId 获取用量解析器。
     * <p>
     * 用于区分 OpenAI 平台下两种端点的不同 usage schema：
     * <ul>
     *   <li>{@code "responses"} → {@link ResponsesUsageParser}（input_tokens / output_tokens / total_tokens）</li>
     *   <li>其他 → {@link OpenAiUsageParser}（prompt_tokens / completion_tokens）</li>
     * </ul>
     * 非 OpenAI 平台忽略 formatId 参数，与 {@link #getUsageParser(Platform)} 行为一致。
     *
     * @param platform   账号平台
     * @param formatId   上游请求格式 ID（{@code "chat_completions"} / {@code "responses"} 等），可为 null
     * @return 对应的 UsageParser；找不到时抛出 IllegalArgumentException
     */
    public IUsageParser getUsageParser(Platform platform, String formatId) {
        if (platform == Platform.OPENAI && "responses".equals(formatId)) {
            return responsesUsageParser;
        }
        return getUsageParser(platform);
    }

    /**
     * 根据上游路由计划获取用量解析器。
     * <p>
     * OpenAI 平台的 Responses 与 Chat Completions usage schema 不同，必须使用 route.usageFormat 精确区分。
     */
    public IUsageParser getUsageParser(UpstreamRoute route) {
        if (route == null) {
            throw new IllegalArgumentException("UpstreamRoute is required");
        }
        return getUsageParser(route.upstreamPlatform(), route.usageFormat());
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
