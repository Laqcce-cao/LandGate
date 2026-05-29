package com.landgate.trigger.gateway.route;

import com.landgate.domain.account.model.entity.AccountEntity;
import com.landgate.types.enums.Platform;
import lombok.Builder;

/**
 * 上游路由请求 —— 路由策略的输入参数。
 * <p>
 * 该对象不包含 Servlet 请求/响应，保证策略可单元测试且不依赖 Web 容器。
 */
@Builder
public record UpstreamRouteRequest(
        /** 已选中的上游账号。 */
        AccountEntity account,
        /** 客户端入口平台，由 URL 路径解析得到。 */
        Platform requestPlatform,
        /** 客户端入口格式，由 URL 路径解析得到。 */
        String requestFormat,
        /** Gemini 等平台使用的原始上游路径。 */
        String upstreamPath,
        /** 客户端请求模型名。 */
        String requestedModel
) {
}
