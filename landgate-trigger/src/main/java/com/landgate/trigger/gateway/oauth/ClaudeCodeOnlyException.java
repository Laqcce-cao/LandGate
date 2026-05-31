package com.landgate.trigger.gateway.oauth;

/**
 * claude_code_only 分组降级链路末端无可用 fallback 时抛出的异常。
 * <p>
 * 参照：sub2api 的 claude_code_only 分组拒绝逻辑。
 */
public class ClaudeCodeOnlyException extends RuntimeException {

    public ClaudeCodeOnlyException(String message) {
        super(message);
    }
}
