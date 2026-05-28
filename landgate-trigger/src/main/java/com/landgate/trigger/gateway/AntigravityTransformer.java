package com.landgate.trigger.gateway;

import com.landgate.domain.account.model.entity.AccountEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.http.HttpRequest;

/**
 * Antigravity 协议请求转换器 —— 构建转发至 Antigravity 上游的请求。
 * <p>
 * Antigravity 协议与 Anthropic Messages API 完全兼容（请求/响应 schema 一致），
 * 因此本类继承 {@link AnthropicTransformer} 并复用其 URL 构建、token 注入和标准 header 设置。
 * <p>
 * 与 {@link AnthropicTransformer} 的唯一区别：Antigravity 上游不属于 Anthropic 官方服务，
 * <strong>不能</strong>对其应用 Claude Code 客户端伪装逻辑（参照 Sub2API 中独立的
 * AntigravityGatewayService 分支）。本类通过 Handler 层的路由隔离实现该约束 ——
 * Antigravity 请求经由 {@link AntigravityGatewayHandler} 处理，
 * 任何后续 Phase 引入的"OAuth 伪装"代码必须以 {@code account.getPlatform() == Platform.ANTHROPIC}
 * 作为前置条件，从而天然跳过 Antigravity 账号。
 * <p>
 * 当前 LandGate 尚未实现 OAuth 伪装功能，本类的实际行为与基类完全一致；
 * 引入独立类是为了在 Handler/Router 层提供清晰的扩展点，便于未来按需差异化。
 */
@Slf4j
@Component
public class AntigravityTransformer extends AnthropicTransformer {

    @Override
    public HttpRequest buildUpstreamRequest(String body, AccountEntity account, String accessToken) {
        // 直接复用 Anthropic 上游请求构建逻辑（URL + Authorization + anthropic-version 等）。
        // 不在此处注入任何伪装头或重写 body —— 详见类级 Javadoc 中关于 Handler 层隔离的说明。
        return super.buildUpstreamRequest(body, account, accessToken);
    }
}
