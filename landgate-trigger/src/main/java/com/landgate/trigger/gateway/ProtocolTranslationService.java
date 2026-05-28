package com.landgate.trigger.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.landgate.trigger.gateway.converter.ConverterRegistry;
import com.landgate.trigger.gateway.converter.ProtocolConverter;
import com.landgate.types.enums.Platform;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 协议翻译服务 —— Hub-and-Spoke 架构的翻译入口。
 * <p>
 * 委托给 {@link ConverterRegistry} 中的 {@link ProtocolConverter} 完成翻译，
 * 翻译路径为：客户端格式 → IR（OpenAI Responses 格式） → 上游格式。
 * <p>
 * 此类不再维护任何点对点（Anthropic↔OpenAI、Chat↔Responses 等）的直接转换逻辑，
 * 所有字段级映射统一下沉到各 {@link ProtocolConverter} 实现中。
 */
@Slf4j
@Component
public class ProtocolTranslationService {

    private final ConverterRegistry converterRegistry;

    public ProtocolTranslationService(ConverterRegistry converterRegistry) {
        this.converterRegistry = converterRegistry;
    }

    // ========================
    // Hub-and-Spoke 请求翻译
    // ========================

    /**
     * 将请求 body 从客户端格式翻译为上游账号格式（Hub-and-Spoke）。
     * <p>
     * 翻译路径：客户端格式 → IR → 上游格式。
     * 若客户端格式与上游格式相同，直接返回原 body。
     *
     * @param body 客户端请求 JSON 字符串
     * @param from 客户端平台（请求格式）
     * @param to   上游账号平台（目标格式）
     * @return 翻译后的请求 JSON 字符串
     */
    public String translateRequest(String body, Platform from, Platform to) {
        if (from == to) return body;

        String fromFormat = platformToFormatId(from);
        String toFormat = platformToFormatId(to);
        if (fromFormat == null || toFormat == null) {
            log.debug("No format mapping for {}→{}, passing through", from, to);
            return body;
        }

        ProtocolConverter clientConv = converterRegistry.get(fromFormat);
        ProtocolConverter upstreamConv = converterRegistry.get(toFormat);
        if (clientConv == null || upstreamConv == null) {
            log.debug("No converter for {}→{}, passing through", from, to);
            return body;
        }

        try {
            // 客户端格式 → IR → 上游格式
            JsonNode ir = clientConv.requestToIR(body);
            return upstreamConv.requestFromIR(ir);
        } catch (Exception e) {
            log.warn("Hub-and-Spoke request translation failed for {}→{}, passing through: {}",
                    from, to, e.getMessage());
            return body;
        }
    }

    // ========================
    // Hub-and-Spoke 响应翻译
    // ========================

    /**
     * 将非流式响应从上游格式翻译为客户端格式（Hub-and-Spoke）。
     * <p>
     * 翻译路径：上游格式 → IR → 客户端格式。
     * 若上游格式与客户端格式相同，直接返回原 body。
     *
     * @param body 上游响应 JSON 字符串
     * @param from 上游账号平台（上游格式）
     * @param to   客户端平台（目标格式）
     * @return 翻译后的响应 JSON 字符串
     */
    public String translateResponse(String body, Platform from, Platform to) {
        if (from == to) return body;

        String fromFormat = platformToFormatId(from);
        String toFormat = platformToFormatId(to);
        if (fromFormat == null || toFormat == null) {
            log.debug("No format mapping for {}→{}, passing through", from, to);
            return body;
        }

        ProtocolConverter upstreamConv = converterRegistry.get(fromFormat);
        ProtocolConverter clientConv = converterRegistry.get(toFormat);
        if (upstreamConv == null || clientConv == null) {
            log.debug("No converter for {}→{}, passing through", from, to);
            return body;
        }

        try {
            // 上游格式 → IR → 客户端格式
            JsonNode ir = upstreamConv.responseToIR(body);
            return clientConv.responseFromIR(ir);
        } catch (Exception e) {
            log.warn("Hub-and-Spoke response translation failed for {}→{}, passing through: {}",
                    from, to, e.getMessage());
            return body;
        }
    }

    // ========================
    // 辅助方法
    // ========================

    /**
     * 将 {@link Platform} 枚举映射为 Converter 的 formatId。
     * <p>
     * 映射关系：
     * <ul>
     *   <li>ANTHROPIC → {@code "messages"}</li>
     *   <li>OPENAI → {@code "chat_completions"}</li>
     *   <li>OPENAI_RESPONSES → {@code "responses"}</li>
     * </ul>
     * 未支持的平台返回 {@code null}（透传模式）。
     * <p>
     * 注意：本方法仅做 Platform → 默认 formatId 的简单映射。
     * 客户端真实请求格式由 {@code GatewayDispatcher} 根据 URL 路径单独识别（Phase 2 引入），
     * 调用方应优先使用 request format 而非依赖此处的映射结果。
     */
    public static String platformToFormatId(Platform platform) {
        return switch (platform) {
            case ANTHROPIC -> "messages";
            case OPENAI -> "chat_completions";
            case OPENAI_RESPONSES -> "responses";
            default -> null;
        };
    }
}
