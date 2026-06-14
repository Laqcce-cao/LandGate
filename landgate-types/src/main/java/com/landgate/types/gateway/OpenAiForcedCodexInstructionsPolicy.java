package com.landgate.types.gateway;

import java.util.List;

/**
 * Sub2API-compatible forced Codex instructions template facts.
 *
 * <p>This policy owns configuration keys and pure template rendering only.
 * It does not read files, parse JSON, choose routes, or mutate requests.</p>
 */
public final class OpenAiForcedCodexInstructionsPolicy {

    public static final String PROPERTY_TEMPLATE_FILE =
            "landgate.gateway.forced-codex-instructions-template-file";
    public static final String PROPERTY_TEMPLATE =
            "landgate.gateway.forced-codex-instructions-template";
    public static final String SUB2API_PROPERTY_TEMPLATE_FILE =
            "landgate.gateway.forced_codex_instructions_template_file";
    public static final String SUB2API_PROPERTY_TEMPLATE =
            "landgate.gateway.forced_codex_instructions_template";

    public static final String LEGACY_PROPERTY_TEMPLATE_FILE =
            "landgate.gateway.codex.forced-instructions-template-file";
    public static final String LEGACY_PROPERTY_TEMPLATE =
            "landgate.gateway.codex.forced-instructions-template";

    public static final String PLACEHOLDER_EXISTING_INSTRUCTIONS = "{{ .ExistingInstructions }}";
    public static final String PLACEHOLDER_ORIGINAL_MODEL = "{{ .OriginalModel }}";
    public static final String PLACEHOLDER_NORMALIZED_MODEL = "{{ .NormalizedModel }}";
    public static final String PLACEHOLDER_BILLING_MODEL = "{{ .BillingModel }}";
    public static final String PLACEHOLDER_UPSTREAM_MODEL = "{{ .UpstreamModel }}";

    private OpenAiForcedCodexInstructionsPolicy() {
    }

    public static List<String> templateFilePropertyKeys() {
        return List.of(PROPERTY_TEMPLATE_FILE, SUB2API_PROPERTY_TEMPLATE_FILE, LEGACY_PROPERTY_TEMPLATE_FILE);
    }

    public static List<String> templatePropertyKeys() {
        return List.of(PROPERTY_TEMPLATE, SUB2API_PROPERTY_TEMPLATE, LEGACY_PROPERTY_TEMPLATE);
    }

    public static String render(String templateText, TemplateData data) {
        if (templateText == null || templateText.isBlank()) {
            return "";
        }
        TemplateData safeData = data == null ? TemplateData.empty() : data;
        return templateText
                .replace(PLACEHOLDER_EXISTING_INSTRUCTIONS, safeData.existingInstructions())
                .replace(PLACEHOLDER_ORIGINAL_MODEL, safeData.originalModel())
                .replace(PLACEHOLDER_NORMALIZED_MODEL, safeData.normalizedModel())
                .replace(PLACEHOLDER_BILLING_MODEL, safeData.billingModel())
                .replace(PLACEHOLDER_UPSTREAM_MODEL, safeData.upstreamModel())
                .trim();
    }

    public record TemplateData(
            String existingInstructions,
            String originalModel,
            String normalizedModel,
            String billingModel,
            String upstreamModel) {

        public TemplateData {
            existingInstructions = normalize(existingInstructions);
            originalModel = normalize(originalModel);
            normalizedModel = normalize(normalizedModel);
            billingModel = normalize(billingModel);
            upstreamModel = normalize(upstreamModel);
        }

        public static TemplateData empty() {
            return new TemplateData("", "", "", "", "");
        }

        private static String normalize(String value) {
            return value == null ? "" : value.trim();
        }
    }
}
