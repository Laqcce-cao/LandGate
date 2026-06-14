package com.landgate.trigger.gateway.transformer;

import com.landgate.types.gateway.OpenAiForcedCodexInstructionsPolicy;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Loads and caches the forced Codex instructions template.
 *
 * <p>The provider owns configuration/file loading only. Rendering is delegated
 * to the type-layer policy and request JSON mutation stays in the Codex body
 * normalizer.</p>
 */
@Component
class OpenAiForcedCodexInstructionsTemplateProvider {

    private final String templateText;

    OpenAiForcedCodexInstructionsTemplateProvider(Environment environment) {
        this.templateText = loadTemplate(environment);
    }

    String templateText() {
        return templateText;
    }

    private static String loadTemplate(Environment environment) {
        String file = firstProperty(environment, OpenAiForcedCodexInstructionsPolicy.templateFilePropertyKeys());
        if (!file.isBlank()) {
            try {
                return Files.readString(Path.of(file), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to read forced Codex instructions template: " + file, e);
            }
        }
        return firstProperty(environment, OpenAiForcedCodexInstructionsPolicy.templatePropertyKeys());
    }

    private static String firstProperty(Environment environment, Iterable<String> keys) {
        if (environment == null) {
            return "";
        }
        for (String key : keys) {
            String value = environment.getProperty(key);
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return "";
    }
}
