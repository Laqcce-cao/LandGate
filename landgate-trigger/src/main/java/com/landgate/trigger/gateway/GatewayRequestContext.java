package com.landgate.trigger.gateway;

import com.landgate.domain.account.model.entity.AccountEntity;
import com.landgate.domain.group.model.entity.GroupEntity;
import com.landgate.types.enums.Platform;
import lombok.Builder;
import lombok.Data;

/**
 * 网关请求上下文 —— 通过 ThreadLocal 存储当前请求的网关元数据。
 * <p>
 * 包含请求 ID、API Key ID、用户 ID、分组、选中的账号、模型名称等信息。
 */
@Data
@Builder
public class GatewayRequestContext {

    private String requestId;
    private Long apiKeyId;
    private Long userId;
    private GroupEntity group;
    private AccountEntity selectedAccount;
    private boolean stream;
    private String requestedModel;
    private String upstreamModel;
    /** 客户端请求格式的平台（由 URL 路径决定），用于判断是否需要协议翻译 */
    private Platform requestPlatform;
    /**
     * 客户端请求的具体格式 ID（由 URL 路径决定）。
     * <p>
     * 取值为 Converter 的 formatId：{@code "messages"}、{@code "chat_completions"}、{@code "responses"}、{@code "gemini"}。
     * 与 {@link #requestPlatform} 的区别：requestFormat 精确到 API 端点（同一个 Platform 下可能有多种格式，
     * 如 OPENAI 平台同时支持 /v1/chat/completions 和 /v1/responses），用于决定流式默认值、上游路径选择等。
     */
    private String requestFormat;
    /** Gemini 专用的上游路径（完整 servlet path），其他平台为 null */
    private String upstreamPath;
    /** 当前请求持有的并发槽位（用于流式续约），非流式请求为 null */
    private ConcurrencySlot concurrencySlot;

    private static final ThreadLocal<GatewayRequestContext> HOLDER = new ThreadLocal<>();

    public static void set(GatewayRequestContext ctx) {
        HOLDER.set(ctx);
    }

    public static GatewayRequestContext get() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
