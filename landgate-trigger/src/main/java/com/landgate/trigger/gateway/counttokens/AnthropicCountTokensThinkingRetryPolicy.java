package com.landgate.trigger.gateway.counttokens;

import com.landgate.trigger.gateway.retry.AnthropicThinkingRetryPolicy;
import org.springframework.stereotype.Component;

/**
 * Backwards-compatible bean name for count_tokens callers.
 *
 * <p>The shared Sub2API-compatible thinking retry behavior lives in
 * {@link AnthropicThinkingRetryPolicy} so the main gateway and count_tokens path
 * cannot drift.</p>
 */
@Component
public class AnthropicCountTokensThinkingRetryPolicy extends AnthropicThinkingRetryPolicy {
}
