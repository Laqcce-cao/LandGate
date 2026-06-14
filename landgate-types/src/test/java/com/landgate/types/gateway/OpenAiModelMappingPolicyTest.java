package com.landgate.types.gateway;

import com.landgate.types.enums.Platform;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("OpenAiModelMappingPolicy")
class OpenAiModelMappingPolicyTest {

    @Test
    @DisplayName("model_mapping supports exact and longest suffix-wildcard matches")
    void modelMappingSupportsExactAndLongestWildcardMatches() {
        OpenAiModelMappingPolicy.MappedModel missing =
                OpenAiModelMappingPolicy.resolveMappedModel(Platform.OPENAI, null, "gpt-5.4");
        assertFalse(missing.matched());
        assertEquals("gpt-5.4", missing.model());

        OpenAiModelMappingPolicy.MappedModel exact =
                OpenAiModelMappingPolicy.resolveMappedModel(
                        Platform.OPENAI,
                        """
                        {"model_mapping":{
                          "gpt-5":"gpt-5.4",
                          "invalid":1
                        }}
                        """,
                        "gpt-5");
        assertTrue(exact.matched());
        assertEquals("gpt-5.4", exact.model());

        OpenAiModelMappingPolicy.MappedModel wildcard =
                OpenAiModelMappingPolicy.resolveMappedModel(
                        Platform.OPENAI,
                        """
                        {"model_mapping":{
                          "gpt-*":"fallback",
                          "gpt-5.*":"gpt-5-family",
                          "gpt-5.4*":"gpt-5.4-upstream"
                        }}
                        """,
                        "gpt-5.4-mini");
        assertTrue(wildcard.matched());
        assertEquals("gpt-5.4-upstream", wildcard.model());
    }

    @Test
    @DisplayName("passthrough mappings still report matched and non-OpenAI accounts are ignored")
    void passthroughMappingsStillReportMatchedAndNonOpenAiAccountsAreIgnored() {
        OpenAiModelMappingPolicy.MappedModel passthrough =
                OpenAiModelMappingPolicy.resolveMappedModel(
                        Platform.OPENAI,
                        "{\"model_mapping\":{\"gpt-*\":\"gpt-5.4\"}}",
                        "gpt-5.4");
        assertTrue(passthrough.matched());
        assertEquals("gpt-5.4", passthrough.model());

        OpenAiModelMappingPolicy.MappedModel ignored =
                OpenAiModelMappingPolicy.resolveMappedModel(
                        Platform.ANTHROPIC,
                        "{\"model_mapping\":{\"gpt-*\":\"mapped\"}}",
                        "gpt-5.4");
        assertFalse(ignored.matched());
        assertEquals("gpt-5.4", ignored.model());
    }
}
