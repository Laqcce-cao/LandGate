package com.landgate.trigger.gateway;

import com.landgate.domain.account.model.entity.AccountEntity;

import java.net.http.HttpRequest;

/**
 * 上游请求转换器接口 —— 将客户端请求构建为上游 AI 提供商的 HTTP 请求。
 */
public interface IRequestTransformer {

    HttpRequest buildUpstreamRequest(String body, AccountEntity account, String accessToken);

    String extractModel(String body);

    boolean isStreamRequest(String body);
}
