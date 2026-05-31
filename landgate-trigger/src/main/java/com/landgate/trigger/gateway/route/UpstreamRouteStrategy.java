package com.landgate.trigger.gateway.route;

/**
 * 上游路由策略 —— 根据账号平台、账号类型和客户端请求格式解析真实上游路由。
 */
public interface UpstreamRouteStrategy {

    /**
     * 判断当前策略是否支持该路由请求。
     *
     * @param request 路由请求
     * @return true 表示可由当前策略解析
     */
    boolean supports(UpstreamRouteRequest request);

    /**
     * 解析上游路由计划。
     *
     * @param request 路由请求
     * @return 上游路由计划
     */
    UpstreamRoute resolve(UpstreamRouteRequest request);
}
