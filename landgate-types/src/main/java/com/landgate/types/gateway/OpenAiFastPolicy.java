package com.landgate.types.gateway;

import com.landgate.types.enums.AccountType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Sub2API-compatible OpenAI service_tier policy facts.
 *
 * <p>This policy owns only pure decisions for body-level {@code service_tier}.
 * It must not read settings, mutate JSON, build requests, select accounts,
 * perform auth, or calculate billing.</p>
 */
public final class OpenAiFastPolicy {

    public static final String ACTION_PASS = "pass";
    public static final String ACTION_FILTER = "filter";
    public static final String ACTION_BLOCK = "block";

    public static final String SCOPE_ALL = "all";
    public static final String SCOPE_OAUTH = "oauth";
    public static final String SCOPE_API_KEY = "apikey";
    public static final String SCOPE_BEDROCK = "bedrock";

    public static final String TIER_ANY = "all";
    public static final String TIER_PRIORITY = OpenAiResponsesBodyPolicy.SERVICE_TIER_PRIORITY;
    public static final String TIER_FLEX = OpenAiResponsesBodyPolicy.SERVICE_TIER_FLEX;

    public static final String PROPERTY_SETTINGS_JSON = "landgate.gateway.openai.fast-policy.settings";
    public static final String FIELD_RULES = "rules";
    public static final String FIELD_SERVICE_TIER = "service_tier";
    public static final String FIELD_ACTION = "action";
    public static final String FIELD_SCOPE = "scope";
    public static final String FIELD_ERROR_MESSAGE = "error_message";
    public static final String FIELD_MODEL_WHITELIST = "model_whitelist";
    public static final String FIELD_FALLBACK_ACTION = "fallback_action";
    public static final String FIELD_FALLBACK_ERROR_MESSAGE = "fallback_error_message";

    public static final String DEFAULT_BLOCK_MESSAGE_TEMPLATE =
            "openai service_tier=%s is not allowed for model %s";

    private OpenAiFastPolicy() {
    }

    public static String defaultActionForNormalizedTier(String normalizedTier) {
        if (TIER_PRIORITY.equals(normalizedTier)) {
            return ACTION_FILTER;
        }
        return ACTION_PASS;
    }

    public static boolean defaultShouldFilter(String normalizedTier) {
        return ACTION_FILTER.equals(defaultActionForNormalizedTier(normalizedTier));
    }

    public static Settings defaultSettings() {
        return new Settings(List.of(new Rule(
                TIER_PRIORITY,
                ACTION_FILTER,
                SCOPE_ALL,
                "",
                List.of(),
                ACTION_PASS,
                "")));
    }

    public static Decision evaluate(Settings settings,
                                    AccountType accountType,
                                    String model,
                                    String normalizedTier) {
        String tier = normalize(normalizedTier);
        if (tier.isBlank()) {
            return Decision.pass();
        }
        Settings effective = settings == null ? defaultSettings() : settings;
        for (Rule rule : effective.rules()) {
            if (rule == null) {
                continue;
            }
            if (!scopeMatches(rule.scope(), accountType)) {
                continue;
            }
            String ruleTier = normalize(rule.serviceTier());
            if (!ruleTier.isBlank() && !TIER_ANY.equals(ruleTier) && !ruleTier.equals(tier)) {
                continue;
            }
            return resolveRuleAction(rule, model);
        }
        return Decision.pass();
    }

    public static boolean scopeMatches(String scope, AccountType accountType) {
        String normalized = normalize(scope);
        if (normalized.isBlank() || SCOPE_ALL.equals(normalized)) {
            return true;
        }
        if (SCOPE_OAUTH.equals(normalized)) {
            return accountType == AccountType.OAUTH;
        }
        if (SCOPE_API_KEY.equals(normalized)) {
            return accountType == AccountType.API_KEY;
        }
        if (SCOPE_BEDROCK.equals(normalized)) {
            return accountType == AccountType.BEDROCK;
        }
        return false;
    }

    public static String defaultBlockMessage(String tier, String model) {
        return DEFAULT_BLOCK_MESSAGE_TEMPLATE.formatted(
                normalize(tier),
                model == null ? "" : model.trim());
    }

    public static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static Decision resolveRuleAction(Rule rule, String model) {
        if (rule.modelWhitelist().isEmpty()) {
            return new Decision(normalizeAction(rule.action()), emptyToBlank(rule.errorMessage()));
        }
        if (matchesModelWhitelist(model, rule.modelWhitelist())) {
            return new Decision(normalizeAction(rule.action()), emptyToBlank(rule.errorMessage()));
        }
        if (!normalize(rule.fallbackAction()).isBlank()) {
            return new Decision(normalizeAction(rule.fallbackAction()), emptyToBlank(rule.fallbackErrorMessage()));
        }
        return Decision.pass();
    }

    public static boolean matchesModelWhitelist(String model, List<String> whitelist) {
        String candidate = model == null ? "" : model.trim();
        if (candidate.isBlank() || whitelist == null || whitelist.isEmpty()) {
            return false;
        }
        for (String pattern : whitelist) {
            String normalizedPattern = pattern == null ? "" : pattern.trim();
            if (normalizedPattern.isBlank()) {
                continue;
            }
            if ("*".equals(normalizedPattern)) {
                return true;
            }
            if (normalizedPattern.endsWith("*")) {
                String prefix = normalizedPattern.substring(0, normalizedPattern.length() - 1);
                if (candidate.startsWith(prefix)) {
                    return true;
                }
            } else if (candidate.equals(normalizedPattern)) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeAction(String action) {
        String normalized = normalize(action);
        if (ACTION_FILTER.equals(normalized) || ACTION_BLOCK.equals(normalized)) {
            return normalized;
        }
        return ACTION_PASS;
    }

    private static String emptyToBlank(String value) {
        return value == null ? "" : value.trim();
    }

    public record Settings(List<Rule> rules) {
        public Settings {
            rules = rules == null ? List.of() : List.copyOf(rules);
        }
    }

    public record Rule(String serviceTier,
                       String action,
                       String scope,
                       String errorMessage,
                       List<String> modelWhitelist,
                       String fallbackAction,
                       String fallbackErrorMessage) {
        public Rule {
            modelWhitelist = modelWhitelist == null
                    ? List.of()
                    : List.copyOf(new ArrayList<>(modelWhitelist));
        }
    }

    public record Decision(String action, String message) {
        public Decision {
            action = normalizeAction(action);
            message = emptyToBlank(message);
        }

        public static Decision pass() {
            return new Decision(ACTION_PASS, "");
        }

        public boolean filters() {
            return ACTION_FILTER.equals(action);
        }

        public boolean blocks() {
            return ACTION_BLOCK.equals(action);
        }
    }
}
