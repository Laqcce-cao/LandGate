package com.landgate.trigger.gateway;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

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
     * 重试规则列表：命中任一规则 → 转回 failover 循环。
     * <p>
     * 每个规则匹配条件：statusCode 在 codes 中 AND（keywords 为空 OR body 包含任一关键字）。
     */
    private static final List<RetryRule> RETRY_RULES = List.of(
            new RetryRule(Set.of(400, 404),
                    Set.of("model_not_found", "model not found", "deprecated", "unknown model")),
            new RetryRule(Set.of(403),
                    Set.of("unsupported_country", "unsupported_region", "region_not_supported",
                            "not available in your country", "not available in your region")),
            new RetryRule(Set.of(400, 404),
                    Set.of("invalid_model", "no such model", "model is not supported"))
    );

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

        for (RetryRule rule : RETRY_RULES) {
            if (!rule.codes.contains(statusCode)) {
                continue;
            }
            if (rule.keywords.isEmpty()) {
                log.debug("Error passthrough RETRY: status={}, platform={}, rule=wildcard", statusCode, platform);
                return ErrorAction.RETRY;
            }
            for (String keyword : rule.keywords) {
                if (lowerBody.contains(keyword.toLowerCase())) {
                    log.info("Error passthrough RETRY: status={}, platform={}, keyword={}",
                            statusCode, platform, keyword);
                    return ErrorAction.RETRY;
                }
            }
        }

        return ErrorAction.MASK;
    }

    /**
     * 从上游 JSON error body 中提取安全的错误消息。
     * <p>
     * 尝试提取常见 error 字段，失败时返回静态通用消息。
     */
    public String extractSafeMessage(int statusCode, String responseBody) {
        if (responseBody == null || responseBody.isEmpty()) {
            return defaultMessage(statusCode);
        }

        // 对上游账户类错误（402/429/503），不提取原始消息，
        // 避免透传"余额不足"等误导性信息给客户端
        if (statusCode == 402 || statusCode == 429 || statusCode == 503) {
            return defaultMessage(statusCode);
        }

        // 尝试从常见 JSON error 路径提取消息
        String[] jsonPaths = {
                "\"message\":\"",           // Anthropic / OpenAI / Gemini 通用
                "\"error\":{\"message\":\"", // OpenAI 嵌套
        };

        for (String path : jsonPaths) {
            int idx = responseBody.indexOf(path);
            if (idx >= 0) {
                int start = idx + path.length();
                int end = responseBody.indexOf('"', start);
                if (end > start) {
                    String msg = responseBody.substring(start, end);
                    // 截断过长消息
                    if (msg.length() > 200) {
                        msg = msg.substring(0, 200) + "...";
                    }
                    return msg;
                }
            }
        }

        return defaultMessage(statusCode);
    }

    private static String defaultMessage(int statusCode) {
        return switch (statusCode) {
            case 400 -> "Invalid request. Please check your parameters.";
            case 401 -> "Authentication failed. Please check your credentials.";
            case 402 -> "The service is temporarily unavailable. Please try again later.";
            case 403 -> "Access denied. Your account may not have permission.";
            case 404 -> "The requested resource was not found.";
            case 422 -> "The request could not be processed.";
            case 429 -> "Too many requests. Please slow down.";
            case 503 -> "The service is temporarily unavailable. Please try again later.";
            default -> "An upstream error occurred. Status: " + statusCode;
        };
    }

    // ---- 类型定义 ----

    public enum ErrorAction {
        /** 转回 failover 循环，切换账户重试 */
        RETRY,
        /** 安全格式化错误消息，不暴露上游细节 */
        MASK
    }

    /**
     * 重试规则：状态码集 + 关键字集（AND 语义）。
     * keywords 为空表示匹配该状态码的全部错误。
     */
    private record RetryRule(Set<Integer> codes, Set<String> keywords) {}
}
