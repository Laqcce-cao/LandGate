package com.landgate.trigger.gateway.error;

import com.landgate.types.gateway.ErrorResponsePolicy;
import com.landgate.types.gateway.OpenAiUpstreamErrorPolicy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 错误选择性透传服务 —— 决定上游非重试错误是转回 failover 循环还是安全格式化。
 * <p>
 * 避免盲透传上游 error body 泄露提供商信息（API 域名、账户标识等），
 * 同时将部分"换账户可恢复"的错误（model_deprecated、unsupported_region）转回重试。
 * <p>
 * 匹配规则：HTTP 状态码 + body 关键字（AND 语义），任一规则命中即生效。
 */
@Slf4j
@Component
public class ErrorPassthroughService {

    /**
     * 对上游错误做出裁决。
     *
     * @param statusCode   上游 HTTP 状态码
     * @param responseBody 上游响应 body（JSON 字符串）
     * @param platform     平台名称（ANTHROPIC / OPENAI / GEMINI）
     * @return RETRY 表示应转回 failover 循环，MASK 表示应安全格式化错误
     */
    public ErrorAction decide(int statusCode, String responseBody, String platform) {
        if (responseBody == null || responseBody.isEmpty()) {
            return ErrorAction.MASK;
        }

        String lowerBody = responseBody.toLowerCase();

        for (ErrorResponsePolicy.RetryRule rule : ErrorResponsePolicy.retryRules()) {
            if (!rule.codes().contains(statusCode)) {
                continue;
            }
            if (rule.keywords().isEmpty()) {
                log.debug("Error passthrough RETRY: status={}, platform={}, rule=wildcard", statusCode, platform);
                return ErrorAction.RETRY;
            }
            for (String keyword : rule.keywords()) {
                if (lowerBody.contains(keyword.toLowerCase())) {
                    log.debug("Error passthrough RETRY: status={}, platform={}, keyword={}",
                            statusCode, platform, keyword);
                    return ErrorAction.RETRY;
                }
            }
        }

        if (isOpenAi(platform)
                && OpenAiUpstreamErrorPolicy.shouldFailover(
                statusCode, ErrorResponsePolicy.extractUpstreamErrorMessage(responseBody), responseBody)) {
            log.debug("Error passthrough RETRY: status={}, platform={}, rule=openai_upstream_failover",
                    statusCode, platform);
            return ErrorAction.RETRY;
        }

        return ErrorAction.MASK;
    }

    private static boolean isOpenAi(String platform) {
        return platform != null && "OPENAI".equalsIgnoreCase(platform.trim());
    }

    /**
     * 从上游 JSON error body 中提取安全的错误消息。
     * <p>
     * 尝试提取常见 error 字段，失败时返回静态通用消息。
     */
    public String extractSafeMessage(int statusCode, String responseBody) {
        return ErrorResponsePolicy.safeMessageForStatus(statusCode, responseBody);
    }

    // ---- 类型定义 ----

    public enum ErrorAction {
        /** 转回 failover 循环，切换账户重试 */
        RETRY,
        /** 安全格式化错误消息，不暴露上游细节 */
        MASK
    }

}
