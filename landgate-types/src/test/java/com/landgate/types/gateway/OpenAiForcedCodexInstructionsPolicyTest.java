package com.landgate.types.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("OpenAI forced Codex instructions policy tests")
class OpenAiForcedCodexInstructionsPolicyTest {

    @Test
    @DisplayName("renders Sub2API-compatible template placeholders")
    void rendersKnownTemplatePlaceholders() {
        String rendered = OpenAiForcedCodexInstructionsPolicy.render("""
                prefix {{ .ExistingInstructions }}
                original={{ .OriginalModel }}
                normalized={{ .NormalizedModel }}
                billing={{ .BillingModel }}
                upstream={{ .UpstreamModel }}
                """, new OpenAiForcedCodexInstructionsPolicy.TemplateData(
                "client system",
                "claude-sonnet-4-5",
                "gpt-5.4",
                "gpt-5.4-codex",
                "gpt-5.4"));

        assertEquals("""
                prefix client system
                original=claude-sonnet-4-5
                normalized=gpt-5.4
                billing=gpt-5.4-codex
                upstream=gpt-5.4""", rendered);
    }

    @Test
    @DisplayName("exposes primary and compatibility property keys")
    void exposesTemplatePropertyKeys() {
        assertTrue(OpenAiForcedCodexInstructionsPolicy.templateFilePropertyKeys()
                .contains(OpenAiForcedCodexInstructionsPolicy.PROPERTY_TEMPLATE_FILE));
        assertTrue(OpenAiForcedCodexInstructionsPolicy.templateFilePropertyKeys()
                .contains(OpenAiForcedCodexInstructionsPolicy.SUB2API_PROPERTY_TEMPLATE_FILE));
        assertTrue(OpenAiForcedCodexInstructionsPolicy.templateFilePropertyKeys()
                .contains(OpenAiForcedCodexInstructionsPolicy.LEGACY_PROPERTY_TEMPLATE_FILE));
        assertTrue(OpenAiForcedCodexInstructionsPolicy.templatePropertyKeys()
                .contains(OpenAiForcedCodexInstructionsPolicy.PROPERTY_TEMPLATE));
        assertTrue(OpenAiForcedCodexInstructionsPolicy.templatePropertyKeys()
                .contains(OpenAiForcedCodexInstructionsPolicy.SUB2API_PROPERTY_TEMPLATE));
        assertTrue(OpenAiForcedCodexInstructionsPolicy.templatePropertyKeys()
                .contains(OpenAiForcedCodexInstructionsPolicy.LEGACY_PROPERTY_TEMPLATE));
    }
}
