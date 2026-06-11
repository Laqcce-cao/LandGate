package com.landgate.trigger.gateway;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * 网关处理入口接口 —— 端到端处理一个网关代理请求。
 */
public interface IGatewayHandler {

    void handle(String body, HttpServletRequest request, HttpServletResponse response) throws IOException;
}
