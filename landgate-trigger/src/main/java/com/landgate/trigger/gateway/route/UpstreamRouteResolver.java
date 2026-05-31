package com.landgate.trigger.gateway.route;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 上游路由解析器 —— 按策略注册顺序选择第一个匹配策略。
 */
@Component
public class UpstreamRouteResolver {

    private final List<UpstreamRouteStrategy> strategies;

    public UpstreamRouteResolver(List<UpstreamRouteStrategy> strategies) {
        this.strategies = strategies;
    }

    /**
     * 解析选中账号对应的上游路由计划。
     *
     * @param request 路由请求
     * @return 上游路由计划
     */
    public UpstreamRoute resolve(UpstreamRouteRequest request) {
        for (UpstreamRouteStrategy strategy : strategies) {
            if (strategy.supports(request)) {
                return strategy.resolve(request);
            }
        }
        throw new IllegalArgumentException("No upstream route strategy for account platform: "
                + (request != null && request.account() != null ? request.account().getPlatform() : null));
    }
}
