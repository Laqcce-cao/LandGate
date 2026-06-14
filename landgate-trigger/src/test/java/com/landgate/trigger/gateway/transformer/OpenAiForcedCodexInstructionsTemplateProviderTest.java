package com.landgate.trigger.gateway.transformer;

import com.landgate.types.gateway.OpenAiForcedCodexInstructionsPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("OpenAI forced Codex instructions template provider tests")
class OpenAiForcedCodexInstructionsTemplateProviderTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("loads template file once and uses cached content")
    void loadsTemplateFileOnce() throws Exception {
        Path templateFile = tempDir.resolve("codex-instructions.md.tmpl");
        Files.writeString(templateFile, "server-prefix\n\n{{ .ExistingInstructions }}");
        MockEnvironment environment = new MockEnvironment()
                .withProperty(OpenAiForcedCodexInstructionsPolicy.PROPERTY_TEMPLATE_FILE, templateFile.toString());

        OpenAiForcedCodexInstructionsTemplateProvider provider =
                new OpenAiForcedCodexInstructionsTemplateProvider(environment);
        Files.writeString(templateFile, "changed");

        assertEquals("server-prefix\n\n{{ .ExistingInstructions }}", provider.templateText());
    }

    @Test
    @DisplayName("inline template is used when no template file is configured")
    void inlineTemplateFallback() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty(OpenAiForcedCodexInstructionsPolicy.PROPERTY_TEMPLATE, "inline {{ .ExistingInstructions }}");

        OpenAiForcedCodexInstructionsTemplateProvider provider =
                new OpenAiForcedCodexInstructionsTemplateProvider(environment);

        assertEquals("inline {{ .ExistingInstructions }}", provider.templateText());
    }

    @Test
    @DisplayName("supports Sub2API snake_case property names")
    void sub2ApiSnakeCasePropertyNames() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty(OpenAiForcedCodexInstructionsPolicy.SUB2API_PROPERTY_TEMPLATE,
                        "snake {{ .ExistingInstructions }}");

        OpenAiForcedCodexInstructionsTemplateProvider provider =
                new OpenAiForcedCodexInstructionsTemplateProvider(environment);

        assertEquals("snake {{ .ExistingInstructions }}", provider.templateText());
    }
}
