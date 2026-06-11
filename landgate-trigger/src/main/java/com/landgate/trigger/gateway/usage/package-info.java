/**
 * Gateway usage parsers.
 *
 * <p>Parsers extract upstream provider token usage into {@code UsageTokens}.
 * Usage parsing is based on the upstream response format, not the client request format.
 * For streaming responses, {@code parseSSELine} receives the SSE {@code data:} payload
 * with the prefix removed.
 */
package com.landgate.trigger.gateway.usage;
