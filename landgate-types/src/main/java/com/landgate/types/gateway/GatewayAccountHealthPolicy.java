package com.landgate.types.gateway;

import java.util.Optional;

/**
 * Stable gateway account-health decisions for retryable upstream statuses.
 *
 * <p>This policy owns pure status/header-to-health-action mapping only. It must
 * not select accounts, mutate account state, read HTTP bodies, write responses,
 * or perform failover.</p>
 */
public final class GatewayAccountHealthPolicy {

    public static final long DEFAULT_RATE_LIMIT_COOLDOWN_SECONDS = 10;
    public static final long OVERLOADED_COOLDOWN_SECONDS = 30;
    public static final long SERVICE_UNAVAILABLE_COOLDOWN_SECONDS = 60;
    public static final long CONSECUTIVE_5XX_COOLDOWN_SECONDS = 120;
    public static final int CONSECUTIVE_5XX_FAILOVER_THRESHOLD = 2;

    public static final String REASON_UPSTREAM_503 = "Upstream 503 at failover=%d";
    public static final String REASON_CONSECUTIVE_5XX = "Consecutive 5xx at failover=%d";

    private GatewayAccountHealthPolicy() {
    }

    public static Decision decideRetryableStatus(int statusCode,
                                                 int failoverCount,
                                                 Optional<String> retryAfterHeader) {
        if (statusCode == OpenAiUpstreamErrorPolicy.STATUS_TOO_MANY_REQUESTS) {
            return new Decision(
                    Action.RATE_LIMITED,
                    parseRetryAfterSeconds(retryAfterHeader).orElse(DEFAULT_RATE_LIMIT_COOLDOWN_SECONDS),
                    retryAfterHeader != null && retryAfterHeader.isPresent(),
                    "");
        }
        if (statusCode == OpenAiUpstreamErrorPolicy.STATUS_OVERLOADED) {
            return new Decision(Action.OVERLOADED, OVERLOADED_COOLDOWN_SECONDS, false, "");
        }
        if (statusCode == 503) {
            return new Decision(Action.TEMP_UNSCHEDULABLE, SERVICE_UNAVAILABLE_COOLDOWN_SECONDS, false,
                    REASON_UPSTREAM_503.formatted(failoverCount));
        }
        if (statusCode >= OpenAiUpstreamErrorPolicy.STATUS_SERVER_ERROR_MIN
                && failoverCount >= CONSECUTIVE_5XX_FAILOVER_THRESHOLD) {
            return new Decision(Action.TEMP_UNSCHEDULABLE, CONSECUTIVE_5XX_COOLDOWN_SECONDS, false,
                    REASON_CONSECUTIVE_5XX.formatted(failoverCount));
        }
        return Decision.none();
    }

    public static Optional<Long> parseRetryAfterSeconds(Optional<String> retryAfterHeader) {
        if (retryAfterHeader == null || retryAfterHeader.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.parseLong(retryAfterHeader.get().trim()));
        } catch (RuntimeException ignored) {
            return Optional.of(DEFAULT_RATE_LIMIT_COOLDOWN_SECONDS);
        }
    }

    public enum Action {
        NONE,
        RATE_LIMITED,
        OVERLOADED,
        TEMP_UNSCHEDULABLE
    }

    public record Decision(Action action,
                           long cooldownSeconds,
                           boolean explicitRetryAfter,
                           String reason) {
        public static Decision none() {
            return new Decision(Action.NONE, 0, false, "");
        }

        public boolean applies() {
            return action != Action.NONE;
        }
    }
}
