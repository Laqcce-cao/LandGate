package com.landgate.trigger.gateway.transformer;

import com.landgate.types.gateway.OpenAiResponsesBodyPolicy;
import com.landgate.types.gateway.OpenAiChatCompletionsBodyPolicy;
import com.landgate.types.gateway.OpenAiCodexProfile;

import java.util.Set;

/**
 * OpenAI request normalizer profile facts and small policy helpers.
 */
final class OpenAiNormalizerProfile {

    static final String DEFAULT_CODEX_MODEL = OpenAiCodexProfile.DEFAULT_MODEL;
    static final String DEFAULT_CODEX_INSTRUCTIONS = OpenAiCodexProfile.DEFAULT_INSTRUCTIONS;

    static final String FIELD_PROMPT_CACHE_KEY = OpenAiResponsesBodyPolicy.FIELD_PROMPT_CACHE_KEY;
    static final String FIELD_CONTENT = OpenAiResponsesBodyPolicy.FIELD_CONTENT;
    static final String FIELD_IMAGE_URL = OpenAiResponsesBodyPolicy.FIELD_IMAGE_URL;
    static final String FIELD_INPUT = OpenAiResponsesBodyPolicy.FIELD_INPUT;
    static final String FIELD_MAX_COMPLETION_TOKENS = OpenAiResponsesBodyPolicy.FIELD_MAX_COMPLETION_TOKENS;
    static final String FIELD_MAX_OUTPUT_TOKENS = OpenAiResponsesBodyPolicy.FIELD_MAX_OUTPUT_TOKENS;
    static final String FIELD_PROMPT_CACHE_RETENTION = OpenAiResponsesBodyPolicy.FIELD_PROMPT_CACHE_RETENTION;
    static final String FIELD_SAFETY_IDENTIFIER = OpenAiResponsesBodyPolicy.FIELD_SAFETY_IDENTIFIER;
    static final String FIELD_SERVICE_TIER = OpenAiResponsesBodyPolicy.FIELD_SERVICE_TIER;
    static final String FIELD_STORE = OpenAiResponsesBodyPolicy.FIELD_STORE;
    static final String FIELD_STREAM = OpenAiChatCompletionsBodyPolicy.FIELD_STREAM;
    static final String FIELD_STREAM_OPTIONS = OpenAiChatCompletionsBodyPolicy.FIELD_STREAM_OPTIONS;
    static final String FIELD_INCLUDE_USAGE = OpenAiChatCompletionsBodyPolicy.FIELD_INCLUDE_USAGE;
    static final String FIELD_TEXT_VERBOSITY = OpenAiChatCompletionsBodyPolicy.FIELD_VERBOSITY;
    static final String FIELD_TYPE = OpenAiResponsesBodyPolicy.FIELD_TYPE;
    static final String TYPE_INPUT_IMAGE = OpenAiResponsesBodyPolicy.TYPE_INPUT_IMAGE;

    private OpenAiNormalizerProfile() {
    }

    static Set<String> codexUnsupportedFields() {
        return OpenAiCodexProfile.unsupportedRequestFields();
    }

    static Set<String> publicResponsesUnsupportedFields() {
        return OpenAiResponsesBodyPolicy.publicResponsesUnsupportedFields();
    }

    static String normalizeCodexModel(String model) {
        return OpenAiCodexProfile.normalizeModel(model);
    }

    static String normalizeServiceTier(String raw) {
        return OpenAiResponsesBodyPolicy.normalizeServiceTier(raw);
    }

    static boolean isEmptyBase64DataUri(String raw) {
        return OpenAiResponsesBodyPolicy.isEmptyBase64DataUri(raw);
    }

    static boolean supportsTextVerbosity(String model) {
        return OpenAiCodexProfile.supportsTextVerbosity(model);
    }
}
