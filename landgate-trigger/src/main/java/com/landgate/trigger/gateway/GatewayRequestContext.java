package com.landgate.trigger.gateway;

import com.landgate.domain.account.model.entity.AccountEntity;
import com.landgate.domain.group.model.entity.GroupEntity;
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
