package com.landgate.trigger.gateway.transformer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.landgate.domain.account.model.entity.AccountEntity;
import com.landgate.trigger.gateway.GatewayRequestContext;
import com.landgate.trigger.gateway.compat.OpenAiCompatTodoGuard;
import com.landgate.trigger.gateway.route.UpstreamRoute;
import com.landgate.types.gateway.GatewayProtocolFormat;
import com.landgate.types.gateway.GatewayResponsesRoutePolicy;
import com.landgate.types.gateway.CompatPromptCacheKeyPolicy;
import com.landgate.types.gateway.OpenAiCompatModelPolicy;
import com.landgate.types.gateway.OpenAiCompactAccountPolicy;
import com.landgate.types.gateway.OpenAiCompactRequestBodyPolicy;
import com.landgate.types.gateway.OpenAiCodexBodyShapeProfile;
import com.landgate.types.gateway.OpenAiFastPolicy;
import com.landgate.types.gateway.OpenAiForcedCodexInstructionsPolicy;
import com.landgate.types.gateway.OpenAiResponsesBodyPolicy;
import com.landgate.types.gateway.OpenAiToolContinuationPolicy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Iterator;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static com.landgate.types.gateway.OpenAiCodexBodyShapeProfile.*;

/**
 * OpenAI OAuth Codex request body normalizer.
 *
 * <p>This component owns Codex internal body-shape mutation only. It does not
 * choose routes, build auth headers, translate protocols, parse SSE, or
 * calculate billing.</p>
 */
@Slf4j
@Component
class OpenAiCodexBodyNormalizer {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final Supplier<String> forcedInstructionsTemplateSupplier;
    private final OpenAiFastPolicyProvider fastPolicyProvider;

    OpenAiCodexBodyNormalizer() {
        this(() -> "", null);
    }

    @Autowired
    OpenAiCodexBodyNormalizer(OpenAiForcedCodexInstructionsTemplateProvider templateProvider,
                              OpenAiFastPolicyProvider fastPolicyProvider) {
        this(templateProvider == null ? () -> "" : templateProvider::templateText,
                fastPolicyProvider);
    }

    OpenAiCodexBodyNormalizer(Supplier<String> forcedInstructionsTemplateSupplier) {
        this(forcedInstructionsTemplateSupplier, null);
    }

    OpenAiCodexBodyNormalizer(Supplier<String> forcedInstructionsTemplateSupplier,
                              OpenAiFastPolicyProvider fastPolicyProvider) {
        this.forcedInstructionsTemplateSupplier = forcedInstructionsTemplateSupplier == null
                ? () -> ""
                : forcedInstructionsTemplateSupplier;
        this.fastPolicyProvider = fastPolicyProvider;
    }

    String normalize(String body,
                     AccountEntity account,
                     UpstreamRoute route,
                     String requestId,
                     boolean preservePromptCacheKey) {
        return normalize(body, account, route, requestId, preservePromptCacheKey, null);
    }

    String normalize(String body,
                     AccountEntity account,
                     UpstreamRoute route,
                     String requestId,
                     boolean preservePromptCacheKey,
                     String requestedModel) {
        try {
            ObjectNode root = (ObjectNode) JSON.readTree(body);
            CodexRequestShape beforeShape = CodexRequestShape.from(root, body);
            String originalModel = textValue(root.get(FIELD_MODEL));

            boolean compactModelChanged = false;
            if (isCodexCompactEndpoint(route)) {
                compactModelChanged = applyCompactModelMapping(root, account);
            }
            if (!compactModelChanged) {
                OpenAiModelMappingRequestNormalizer.apply(root, account, FIELD_MODEL);
            }
            if (!compactModelChanged) {
                normalizeCodexOAuthModel(root);
            }
            normalizeCodexReasoningEffort(root);
            for (String field : OpenAiNormalizerProfile.codexUnsupportedFields()) {
                if (shouldRemoveCodexField(field, preservePromptCacheKey)) {
                    root.remove(field);
                }
            }
            normalizeCodexTextVerbosity(root);
            normalizeOpenAIServiceTier(root, account);
            normalizeCodexEndpointFields(root, route);
            if (isCodexCompactEndpoint(route)) {
                retainCompactRequestFields(root);
                sanitizeEmptyBase64InputImages(root);
                String normalized = JSON.writeValueAsString(root);
                CodexRequestShape afterShape = CodexRequestShape.from(root, normalized);
                logCodexNormalizationDiagnostics(requestId, account, route, beforeShape, afterShape);
                return normalized;
            }

            normalizeLegacyFunctionFields(root);
            normalizeCodexTools(root);
            normalizeCodexToolChoice(root);
            extractSystemMessagesToInstructions(root);
            applyForcedCodexInstructionsTemplate(root, route, originalModel, requestedModel);
            if (isBlankText(root.get(FIELD_INSTRUCTIONS))) {
                root.put(FIELD_INSTRUCTIONS, OpenAiNormalizerProfile.DEFAULT_CODEX_INSTRUCTIONS);
            }
            normalizeCodexInput(root, isAnthropicMessagesCompat(route));
            if (isAnthropicMessagesCompat(route)
                    && CompatPromptCacheKeyPolicy.shouldAutoInjectPromptCacheKeyForCompat(
                    OpenAiCompatModelPolicy.resolveAnthropicMessagesCompatModel(
                            account == null ? null : account.getPlatform(),
                            account == null ? null : account.getCredentials(),
                            textValue(root.get(FIELD_MODEL)),
                            requestedModel,
                            textValue(root.get(FIELD_MODEL))))) {
                OpenAiCompatTodoGuard.appendToResponsesRoot(root);
            }
            sanitizeEmptyBase64InputImages(root);

            String normalized = JSON.writeValueAsString(root);
            CodexRequestShape afterShape = CodexRequestShape.from(root, normalized);
            logCodexNormalizationDiagnostics(requestId, account, route, beforeShape, afterShape);
            return normalized;
        } catch (OpenAiFastPolicyBlockedException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Failed to normalize Codex OAuth request body: account_id={}",
                    account != null ? account.getId() : null, e);
            return body;
        }
    }

    private void applyForcedCodexInstructionsTemplate(ObjectNode root,
                                                      UpstreamRoute route,
                                                      String originalModel,
                                                      String requestedModel) {
        if (!isAnthropicMessagesCompat(route)) {
            return;
        }
        String templateText = forcedInstructionsTemplateSupplier.get();
        if (templateText == null || templateText.isBlank()) {
            return;
        }
        String normalizedModel = textValue(root.get(FIELD_MODEL));
        String existingInstructions = textValue(root.get(FIELD_INSTRUCTIONS));
        String upstreamModel = firstNonBlank(normalizedModel, requestedModel, originalModel);
        String rendered = OpenAiForcedCodexInstructionsPolicy.render(templateText,
                new OpenAiForcedCodexInstructionsPolicy.TemplateData(
                        existingInstructions,
                        originalModel,
                        normalizedModel,
                        firstNonBlank(requestedModel, normalizedModel, originalModel),
                        upstreamModel));
        if (rendered.isBlank()) {
            return;
        }
        JsonNode existing = root.get(FIELD_INSTRUCTIONS);
        if (!isBlankText(existing) && existing.asText().trim().equals(rendered)) {
            return;
        }
        root.put(FIELD_INSTRUCTIONS, rendered);
    }

    private static boolean applyCompactModelMapping(ObjectNode root, AccountEntity account) {
        if (account == null) {
            return false;
        }
        JsonNode model = root.get(FIELD_MODEL);
        if (model == null || !model.isTextual()) {
            return false;
        }
        String originalModel = model.asText();
        OpenAiCompactAccountPolicy.CompactModelMapping mapping =
                OpenAiCompactAccountPolicy.resolveCompactMappedModel(
                        account.getCredentials(), originalModel);
        if (mapping.matched() && !mapping.model().equals(originalModel)) {
            root.put(FIELD_MODEL, mapping.model());
            return true;
        }
        return false;
    }

    private static void normalizeCodexEndpointFields(ObjectNode root, UpstreamRoute route) {
        if (isCodexCompactEndpoint(route)) {
            root.remove(OpenAiNormalizerProfile.FIELD_PROMPT_CACHE_KEY);
            root.remove(OpenAiNormalizerProfile.FIELD_STORE);
            root.remove(OpenAiNormalizerProfile.FIELD_STREAM);
            return;
        }
        if (isAnthropicMessagesCompat(route)) {
            root.remove(OpenAiNormalizerProfile.FIELD_PROMPT_CACHE_KEY);
        }
        root.put(OpenAiNormalizerProfile.FIELD_STORE, false);
        // ChatGPT Codex internal Responses endpoint only accepts streaming requests.
        root.put(OpenAiNormalizerProfile.FIELD_STREAM, true);
    }

    private static void retainCompactRequestFields(ObjectNode root) {
        List<String> toRemove = new ArrayList<>();
        Iterator<String> fields = root.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            if (!OpenAiCompactRequestBodyPolicy.isCompactAllowedField(field)) {
                toRemove.add(field);
            }
        }
        root.remove(toRemove);
    }

    private static void normalizeLegacyFunctionFields(ObjectNode root) {
        JsonNode functions = root.get(FIELD_FUNCTIONS);
        if (functions != null && functions.isArray()) {
            ArrayNode tools = root.has(FIELD_TOOLS) && root.get(FIELD_TOOLS).isArray()
                    ? (ArrayNode) root.get(FIELD_TOOLS)
                    : JSON.createArrayNode();
            for (JsonNode function : functions) {
                if (function != null && function.isObject()) {
                    ObjectNode tool = JSON.createObjectNode();
                    tool.put(FIELD_TYPE, TYPE_FUNCTION);
                    tool.set(FIELD_FUNCTION, function);
                    tools.add(tool);
                }
            }
            root.set(FIELD_TOOLS, tools);
            root.remove(FIELD_FUNCTIONS);
        }

        JsonNode functionCall = root.get(FIELD_FUNCTION_CALL);
        if (functionCall == null || functionCall.isNull()) return;
        if (functionCall.isTextual()) {
            String choice = functionCall.asText().trim();
            if (!choice.isBlank()) {
                root.put(FIELD_TOOL_CHOICE, choice);
            }
        } else if (functionCall.isObject() && !isBlankText(functionCall.get(FIELD_NAME))) {
            ObjectNode choice = JSON.createObjectNode();
            choice.put(FIELD_TYPE, TYPE_FUNCTION);
            choice.put(FIELD_NAME, functionCall.get(FIELD_NAME).asText());
            root.set(FIELD_TOOL_CHOICE, choice);
        }
        root.remove(FIELD_FUNCTION_CALL);
    }

    private static void normalizeCodexTools(ObjectNode root) {
        JsonNode toolsNode = root.get(FIELD_TOOLS);
        if (toolsNode == null || !toolsNode.isArray()) return;

        ArrayNode normalized = JSON.createArrayNode();
        for (JsonNode rawTool : toolsNode) {
            if (!(rawTool instanceof ObjectNode tool)) {
                normalized.add(rawTool);
                continue;
            }
            String type = textValue(tool.get(FIELD_TYPE));
            if (!TYPE_FUNCTION.equals(type)) {
                normalized.add(tool);
                continue;
            }

            ObjectNode out = tool.deepCopy();
            JsonNode function = out.get(FIELD_FUNCTION);
            if (function != null && function.isObject()) {
                if (isBlankText(out.get(FIELD_NAME)) && !isBlankText(function.get(FIELD_NAME))) {
                    out.put(FIELD_NAME, function.get(FIELD_NAME).asText());
                }
                if (isBlankText(out.get(FIELD_DESCRIPTION)) && !isBlankText(function.get(FIELD_DESCRIPTION))) {
                    out.put(FIELD_DESCRIPTION, function.get(FIELD_DESCRIPTION).asText());
                }
                if (!out.has(FIELD_PARAMETERS) && function.has(FIELD_PARAMETERS)) {
                    out.set(FIELD_PARAMETERS, function.get(FIELD_PARAMETERS));
                }
                if (!out.has(FIELD_STRICT) && function.has(FIELD_STRICT)) {
                    out.set(FIELD_STRICT, function.get(FIELD_STRICT));
                }
            }
            if (!out.has(FIELD_PARAMETERS) && out.has(FIELD_INPUT_SCHEMA)) {
                out.set(FIELD_PARAMETERS, out.get(FIELD_INPUT_SCHEMA));
                out.remove(FIELD_INPUT_SCHEMA);
            }
            if (isBlankText(out.get(FIELD_NAME))) {
                continue;
            }
            out.set(FIELD_PARAMETERS, normalizeFunctionParameters(out.get(FIELD_PARAMETERS)));
            normalized.add(out);
        }
        root.set(FIELD_TOOLS, normalized);
    }

    private static JsonNode normalizeFunctionParameters(JsonNode parameters) {
        if (!(parameters instanceof ObjectNode object)) {
            ObjectNode empty = JSON.createObjectNode();
            empty.put(FIELD_TYPE, TYPE_OBJECT);
            empty.set(FIELD_PROPERTIES, JSON.createObjectNode());
            return empty;
        }
        ObjectNode copy = object.deepCopy();
        if (TYPE_OBJECT.equals(textValue(copy.get(FIELD_TYPE))) && !copy.has(FIELD_PROPERTIES)) {
            copy.set(FIELD_PROPERTIES, JSON.createObjectNode());
        }
        return copy;
    }

    private static void normalizeCodexToolChoice(ObjectNode root) {
        JsonNode choice = root.get(FIELD_TOOL_CHOICE);
        if (choice == null || choice.isNull()) return;
        if (choice.isTextual()) {
            String value = choice.asText().trim();
            if (!isAllowedTextToolChoice(value)
                    && !codexToolsContainType(root.get(FIELD_TOOLS), value)) {
                root.put(FIELD_TOOL_CHOICE, TOOL_CHOICE_AUTO);
            }
            return;
        }
        if (!(choice instanceof ObjectNode choiceObject)) {
            root.put(FIELD_TOOL_CHOICE, TOOL_CHOICE_AUTO);
            return;
        }

        String type = textValue(choiceObject.get(FIELD_TYPE));
        if (TYPE_FUNCTION.equals(type)) {
            String name = textValue(choiceObject.get(FIELD_NAME));
            JsonNode function = choiceObject.get(FIELD_FUNCTION);
            if (name.isBlank() && function != null && function.isObject()) {
                name = textValue(function.get(FIELD_NAME));
            }
            if (name.isBlank() || !codexToolsContainFunctionName(root.get(FIELD_TOOLS), name)) {
                root.put(FIELD_TOOL_CHOICE, TOOL_CHOICE_AUTO);
                return;
            }
            ObjectNode normalized = JSON.createObjectNode();
            normalized.put(FIELD_TYPE, TYPE_FUNCTION);
            normalized.put(FIELD_NAME, name);
            root.set(FIELD_TOOL_CHOICE, normalized);
            return;
        }
        if (!codexToolsContainType(root.get(FIELD_TOOLS), type)) {
            root.put(FIELD_TOOL_CHOICE, TOOL_CHOICE_AUTO);
        }
    }

    private static boolean codexToolsContainType(JsonNode tools, String type) {
        if (tools == null || !tools.isArray() || type == null || type.isBlank()) return false;
        for (JsonNode tool : tools) {
            if (type.equals(textValue(tool.get(FIELD_TYPE)))) {
                return true;
            }
        }
        return false;
    }

    private static boolean codexToolsContainFunctionName(JsonNode tools, String name) {
        if (tools == null || !tools.isArray() || name == null || name.isBlank()) return false;
        for (JsonNode tool : tools) {
            if (!TYPE_FUNCTION.equals(textValue(tool.get(FIELD_TYPE)))) {
                continue;
            }
            String toolName = textValue(tool.get(FIELD_NAME));
            JsonNode function = tool.get(FIELD_FUNCTION);
            if (toolName.isBlank() && function != null && function.isObject()) {
                toolName = textValue(function.get(FIELD_NAME));
            }
            if (name.equals(toolName)) {
                return true;
            }
        }
        return false;
    }

    private static void normalizeCodexInput(ObjectNode root, boolean preserveCallIds) {
        JsonNode inputNode = root.get(FIELD_INPUT);
        if (inputNode == null || inputNode.isNull()) return;

        if (inputNode.isTextual()) {
            String input = inputNode.asText();
            ArrayNode items = JSON.createArrayNode();
            if (!input.trim().isEmpty()) {
                ObjectNode item = JSON.createObjectNode();
                item.put(FIELD_TYPE, TYPE_MESSAGE);
                item.put(FIELD_ROLE, ROLE_USER);
                item.put(FIELD_CONTENT, input);
                items.add(item);
            }
            root.set(FIELD_INPUT, items);
            return;
        }

        if (!inputNode.isArray()) return;
        ArrayNode normalized = JSON.createArrayNode();
        boolean preserveReferences = OpenAiToolContinuationPolicy.needsToolContinuation(root);
        CodexInputFilterOptions options = new CodexInputFilterOptions(preserveReferences, preserveCallIds);
        for (JsonNode item : inputNode) {
            if (!(item instanceof ObjectNode objectItem)) {
                normalized.add(item);
                continue;
            }
            if (ROLE_TOOL.equals(textValue(objectItem.get(FIELD_ROLE)))) {
                JsonNode converted = convertToolRoleItem(objectItem);
                if (converted instanceof ObjectNode convertedObject) {
                    normalized.add(filterCodexInputItem(convertedObject, options));
                } else {
                    normalized.add(converted);
                }
                continue;
            }
            JsonNode contentNormalized = normalizeMessageContentText(objectItem);
            if (contentNormalized instanceof ObjectNode normalizedObject) {
                JsonNode filtered = filterCodexInputItem(normalizedObject, options);
                if (filtered != null) {
                    normalized.add(filtered);
                }
            } else {
                normalized.add(contentNormalized);
            }
        }
        root.set(FIELD_INPUT, normalized);
    }

    private static JsonNode filterCodexInputItem(ObjectNode item, CodexInputFilterOptions options) {
        String type = textValue(item.get(FIELD_TYPE));
        if (TYPE_REASONING.equals(type)) {
            return null;
        }

        if (TYPE_ITEM_REFERENCE.equals(type)) {
            if (!options.preserveReferences()) {
                return null;
            }
            ObjectNode copy = item.deepCopy();
            String id = textValue(copy.get(FIELD_ID));
            if (id.startsWith(CALL_ID_PREFIX_CALL)) {
                copy.put(FIELD_ID, fixCodexCallIdPrefix(id, options.preserveCallIds()));
            }
            return copy;
        }

        ObjectNode copy = item.deepCopy();
        if (isCodexToolCallItemType(type)) {
            String callId = textValue(copy.get(FIELD_CALL_ID));
            if (callId.isBlank()) {
                String id = textValue(copy.get(FIELD_ID));
                if (!id.isBlank()) {
                    callId = id;
                    copy.put(FIELD_CALL_ID, callId);
                }
            }
            if (!callId.isBlank()) {
                copy.put(FIELD_CALL_ID, fixCodexCallIdPrefix(callId, options.preserveCallIds()));
            }
        } else {
            copy.remove(FIELD_CALL_ID);
        }

        if (codexInputItemRequiresName(type) && textValue(copy.get(FIELD_NAME)).isBlank()) {
            String name = firstNonBlank(textValue(copy.get(FIELD_TOOL_NAME)));
            JsonNode function = copy.get(FIELD_FUNCTION);
            if (name.isBlank() && function != null && function.isObject()) {
                name = textValue(function.get(FIELD_NAME));
            }
            copy.put(FIELD_NAME, name.isBlank() ? ROLE_TOOL : name);
        }

        if (!options.preserveReferences()) {
            copy.remove(FIELD_ID);
        }
        return copy;
    }

    private static String fixCodexCallIdPrefix(String id, boolean preserveCallIds) {
        String value = id == null ? "" : id.trim();
        if (preserveCallIds) {
            return value;
        }
        if (value.isBlank() || value.startsWith(CALL_ID_PREFIX_FC)) {
            return value;
        }
        if (value.startsWith(CALL_ID_PREFIX_CALL)) {
            return CALL_ID_PREFIX_FC + value.substring(CALL_ID_PREFIX_CALL.length());
        }
        return CALL_ID_PREFIX_FC_UNDERSCORE + value;
    }

    private record CodexInputFilterOptions(boolean preserveReferences, boolean preserveCallIds) {
    }

    private static JsonNode convertToolRoleItem(ObjectNode item) {
        String callId = firstNonBlank(
                textValue(item.get(FIELD_CALL_ID)),
                textValue(item.get(FIELD_TOOL_CALL_ID)),
                textValue(item.get(FIELD_ID)));
        if (callId.isBlank()) {
            ObjectNode fallback = item.deepCopy();
            fallback.put(FIELD_ROLE, ROLE_USER);
            fallback.remove(FIELD_TOOL_CALL_ID);
            return normalizeMessageContentText(fallback);
        }

        ObjectNode output = JSON.createObjectNode();
        output.put(FIELD_TYPE, TYPE_FUNCTION_CALL_OUTPUT);
        output.put(FIELD_CALL_ID, callId);
        String text = extractTextFromContent(item.get(FIELD_CONTENT));
        if (text.isBlank() && item.has(FIELD_OUTPUT)) {
            text = textValue(item.get(FIELD_OUTPUT));
        }
        if (text.isBlank() && item.has(FIELD_CONTENT)) {
            text = item.get(FIELD_CONTENT).toString();
        }
        output.put(FIELD_OUTPUT, text);
        return output;
    }

    private static JsonNode normalizeMessageContentText(ObjectNode item) {
        if (!TYPE_MESSAGE.equals(textValue(item.get(FIELD_TYPE)))
                || !item.has(FIELD_CONTENT)
                || !item.get(FIELD_CONTENT).isArray()) {
            return item;
        }
        ObjectNode normalized = item.deepCopy();
        ArrayNode parts = JSON.createArrayNode();
        for (JsonNode part : normalized.get(FIELD_CONTENT)) {
            if (part instanceof ObjectNode partObject && partObject.has(FIELD_TEXT)
                    && !partObject.get(FIELD_TEXT).isTextual()) {
                ObjectNode normalizedPart = partObject.deepCopy();
                normalizedPart.put(FIELD_TEXT, stringifyJsonValue(partObject.get(FIELD_TEXT)));
                parts.add(normalizedPart);
            } else {
                parts.add(part);
            }
        }
        normalized.set(FIELD_CONTENT, parts);
        return normalized;
    }

    private void normalizeOpenAIServiceTier(ObjectNode root, AccountEntity account) {
        JsonNode value = root.get(OpenAiNormalizerProfile.FIELD_SERVICE_TIER);
        if (value == null || !value.isTextual()) {
            return;
        }
        String normalized = OpenAiNormalizerProfile.normalizeServiceTier(value.asText());
        if (normalized.isBlank()) {
            root.remove(OpenAiNormalizerProfile.FIELD_SERVICE_TIER);
            return;
        }
        OpenAiFastPolicy.Decision decision = OpenAiFastPolicy.evaluate(
                fastPolicySettings(),
                account == null ? null : account.getType(),
                textValue(root.get(FIELD_MODEL)),
                normalized);
        if (decision.blocks()) {
            String model = textValue(root.get(FIELD_MODEL));
            String message = decision.message().isBlank()
                    ? OpenAiFastPolicy.defaultBlockMessage(normalized, model)
                    : decision.message();
            throw new OpenAiFastPolicyBlockedException(message, normalized, model);
        }
        if (decision.filters()) {
            root.remove(OpenAiNormalizerProfile.FIELD_SERVICE_TIER);
        } else {
            root.put(OpenAiNormalizerProfile.FIELD_SERVICE_TIER, normalized);
        }
    }

    private OpenAiFastPolicy.Settings fastPolicySettings() {
        return fastPolicyProvider == null ? OpenAiFastPolicy.defaultSettings() : fastPolicyProvider.current();
    }

    private static void normalizeCodexOAuthModel(ObjectNode root) {
        JsonNode modelNode = root.get(FIELD_MODEL);
        if (modelNode == null || !modelNode.isTextual()) {
            return;
        }
        root.put(FIELD_MODEL, OpenAiNormalizerProfile.normalizeCodexModel(modelNode.asText()));
    }

    private static void normalizeCodexReasoningEffort(ObjectNode root) {
        JsonNode reasoning = root.get(FIELD_REASONING);
        if (reasoning instanceof ObjectNode reasoningObject
                && REASONING_EFFORT_MINIMAL.equals(textValue(reasoningObject.get(FIELD_EFFORT)))) {
            reasoningObject.put(FIELD_EFFORT, REASONING_EFFORT_NONE);
        }
    }

    private static void normalizeCodexTextVerbosity(ObjectNode root) {
        String model = textValue(root.get(FIELD_MODEL));
        if (OpenAiNormalizerProfile.supportsTextVerbosity(model)) {
            return;
        }
        JsonNode text = root.get(FIELD_TEXT);
        if (text instanceof ObjectNode textObject) {
            textObject.remove(OpenAiNormalizerProfile.FIELD_TEXT_VERBOSITY);
        }
    }

    private static void sanitizeEmptyBase64InputImages(ObjectNode root) {
        JsonNode input = root.get(FIELD_INPUT);
        if (input == null || !input.isArray()) {
            return;
        }
        ArrayNode normalizedItems = JSON.createArrayNode();
        boolean changed = false;
        for (JsonNode item : input) {
            if (!(item instanceof ObjectNode itemObject)) {
                normalizedItems.add(item);
                continue;
            }
            if (shouldDropEmptyBase64InputImagePart(itemObject)) {
                changed = true;
                continue;
            }
            JsonNode content = itemObject.get(FIELD_CONTENT);
            if (content == null || !content.isArray()) {
                normalizedItems.add(item);
                continue;
            }
            ArrayNode normalizedParts = JSON.createArrayNode();
            boolean itemChanged = false;
            for (JsonNode part : content) {
                if (part instanceof ObjectNode partObject && shouldDropEmptyBase64InputImagePart(partObject)) {
                    changed = true;
                    itemChanged = true;
                    continue;
                }
                normalizedParts.add(part);
            }
            if (itemChanged) {
                if (normalizedParts.isEmpty()) {
                    continue;
                }
                ObjectNode copied = itemObject.deepCopy();
                copied.set(FIELD_CONTENT, normalizedParts);
                normalizedItems.add(copied);
            } else {
                normalizedItems.add(item);
            }
        }
        if (changed) {
            root.set(FIELD_INPUT, normalizedItems);
        }
    }

    private static boolean shouldDropEmptyBase64InputImagePart(ObjectNode part) {
        return TYPE_INPUT_IMAGE.equals(textValue(part.get(FIELD_TYPE)))
                && OpenAiResponsesBodyPolicy.isEmptyBase64DataUri(textValue(part.get(FIELD_IMAGE_URL)));
    }

    private static boolean isCodexCompactEndpoint(UpstreamRoute route) {
        return route != null && GatewayResponsesRoutePolicy.isCompactPath(route.targetUrl());
    }

    private static void extractSystemMessagesToInstructions(ObjectNode root) {
        JsonNode inputNode = root.get(FIELD_INPUT);
        if (inputNode == null || !inputNode.isArray()) return;

        List<String> systemTexts = new ArrayList<>();
        ArrayNode filtered = JSON.createArrayNode();
        for (JsonNode item : inputNode) {
            String role = item.has(FIELD_ROLE) ? item.get(FIELD_ROLE).asText() : "";
            if (OpenAiCodexBodyShapeProfile.isSystemOrDeveloperRole(role)) {
                String text = extractTextFromContent(item.get(FIELD_CONTENT));
                if (!text.isBlank()) systemTexts.add(text);
            } else {
                filtered.add(item);
            }
        }
        if (systemTexts.isEmpty()) return;

        String extracted = String.join("\n\n", systemTexts);
        JsonNode existing = root.get(FIELD_INSTRUCTIONS);
        if (!isBlankText(existing)) {
            root.put(FIELD_INSTRUCTIONS, extracted + "\n\n" + existing.asText());
        } else {
            root.put(FIELD_INSTRUCTIONS, extracted);
        }
        root.set(FIELD_INPUT, filtered);
    }

    private static String extractTextFromContent(JsonNode contentNode) {
        if (contentNode == null || contentNode.isNull()) return "";
        if (contentNode.isTextual()) return contentNode.asText();
        if (contentNode.isArray()) {
            List<String> texts = new ArrayList<>();
            for (JsonNode part : contentNode) {
                if (part.has(FIELD_TEXT)) {
                    String text = stringifyJsonValue(part.get(FIELD_TEXT));
                    if (!text.isBlank()) texts.add(text);
                }
            }
            return String.join("\n", texts);
        }
        return contentNode.asText();
    }

    private static String textValue(JsonNode node) {
        return node != null && node.isTextual() ? node.asText().trim() : "";
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return "";
        for (String value : values) {
            if (value != null && !value.isBlank()) return value.trim();
        }
        return "";
    }

    private static String stringifyJsonValue(JsonNode node) {
        if (node == null || node.isNull()) return "";
        if (node.isTextual()) return node.asText();
        return node.toString();
    }

    private static boolean isBlankText(JsonNode node) {
        return node == null || node.isNull() || !node.isTextual() || node.asText().isBlank();
    }

    private static boolean shouldRemoveCodexField(String field, boolean preservePromptCacheKey) {
        return !(OpenAiNormalizerProfile.FIELD_PROMPT_CACHE_KEY.equals(field) && preservePromptCacheKey);
    }

    private static boolean isAnthropicMessagesCompat(UpstreamRoute route) {
        return route != null && GatewayProtocolFormat.MESSAGES.is(route.clientFormat());
    }

    private static void logCodexNormalizationDiagnostics(String requestId,
                                                         AccountEntity account,
                                                         UpstreamRoute route,
                                                         CodexRequestShape before,
                                                         CodexRequestShape after) {
        if (!log.isDebugEnabled()) return;
        GatewayRequestContext ctx = GatewayRequestContext.get();
        String effectiveRequestId = requestId != null && !requestId.isBlank()
                ? requestId
                : (ctx != null ? ctx.getRequestId() : "-");
        String endpoint = route != null && route.endpointKind() != null ? route.endpointKind().name() : "unknown";
        log.debug("[{}] Codex OAuth 请求规范化诊断: account_id={}, endpoint={}, preserve_prompt_cache_key={}, body_bytes={}->{}, body_hash={}->{}, "
                        + "prefix64k_hash={}->{}, prefix128k_hash={}->{}, prefix192k_hash={}->{}, prefix256k_hash={}->{}, prefix384k_hash={}->{}, "
                        + "input_items={}->{}, input_bytes={}->{}, max_input_item={}->{}, tail_input_items={}->{}, "
                        + "system_or_developer_items={}->{}, instructions={}->{}, tools={}->{}, "
                        + "prompt_cache_key={}->{}, prompt_cache_retention={}->{}, previous_response_id={}->{}, conversation={}->{}, "
                        + "metadata={}->{}, store={}->{}, stream={}->{}",
                effectiveRequestId,
                account != null ? account.getId() : null,
                endpoint,
                after.hasPromptCacheKey,
                before.bodyBytes, after.bodyBytes,
                before.bodyHash, after.bodyHash,
                before.prefix64kHash, after.prefix64kHash,
                before.prefix128kHash, after.prefix128kHash,
                before.prefix192kHash, after.prefix192kHash,
                before.prefix256kHash, after.prefix256kHash,
                before.prefix384kHash, after.prefix384kHash,
                before.inputItems, after.inputItems,
                before.inputBytes, after.inputBytes,
                before.maxInputItem, after.maxInputItem,
                before.tailInputItems, after.tailInputItems,
                before.systemOrDeveloperItems, after.systemOrDeveloperItems,
                before.hasInstructions, after.hasInstructions,
                before.toolsItems, after.toolsItems,
                before.hasPromptCacheKey, after.hasPromptCacheKey,
                before.hasPromptCacheRetention, after.hasPromptCacheRetention,
                before.hasPreviousResponseId, after.hasPreviousResponseId,
                before.hasConversation, after.hasConversation,
                before.hasMetadata, after.hasMetadata,
                before.storeValue, after.storeValue,
                before.streamValue, after.streamValue);
    }

    private record CodexRequestShape(
            int bodyBytes,
            String bodyHash,
            String prefix64kHash,
            String prefix128kHash,
            String prefix192kHash,
            String prefix256kHash,
            String prefix384kHash,
            int inputItems,
            int inputBytes,
            String maxInputItem,
            String tailInputItems,
            int systemOrDeveloperItems,
            boolean hasInstructions,
            int toolsItems,
            boolean hasPromptCacheKey,
            boolean hasPromptCacheRetention,
            boolean hasPreviousResponseId,
            boolean hasConversation,
            boolean hasMetadata,
            String storeValue,
            String streamValue
    ) {
        static CodexRequestShape from(ObjectNode root, String body) {
            JsonNode input = root.get(FIELD_INPUT);
            int inputItems = input != null && input.isArray() ? input.size() : -1;
            int inputBytes = input == null ? -1 : input.toString().getBytes(StandardCharsets.UTF_8).length;
            int systemOrDeveloperItems = 0;
            int maxInputIndex = -1;
            int maxInputBytes = -1;
            String maxInputRole = "";
            String maxInputType = "";
            if (input != null && input.isArray()) {
                for (int i = 0; i < input.size(); i++) {
                    JsonNode item = input.get(i);
                    String role = item.path(FIELD_ROLE).asText("");
                    if (OpenAiCodexBodyShapeProfile.isSystemOrDeveloperRole(role)) {
                        systemOrDeveloperItems++;
                    }
                    int itemBytes = item.toString().getBytes(StandardCharsets.UTF_8).length;
                    if (itemBytes > maxInputBytes) {
                        maxInputIndex = i;
                        maxInputBytes = itemBytes;
                        maxInputRole = role;
                        maxInputType = inputItemType(item);
                    }
                }
            }
            JsonNode tools = root.get(FIELD_TOOLS);
            String maxInputItem = maxInputIndex >= 0
                    ? maxInputIndex + ":" + maxInputRole + "/" + maxInputType + "/" + maxInputBytes + "B"
                    : "none";
            return new CodexRequestShape(
                    body == null ? 0 : body.getBytes(StandardCharsets.UTF_8).length,
                    sha256Hex(body, Integer.MAX_VALUE),
                    sha256Hex(body, 64 * 1024),
                    sha256Hex(body, 128 * 1024),
                    sha256Hex(body, 192 * 1024),
                    sha256Hex(body, 256 * 1024),
                    sha256Hex(body, 384 * 1024),
                    inputItems,
                    inputBytes,
                    maxInputItem,
                    tailInputItems(input),
                    systemOrDeveloperItems,
                    !isBlankText(root.get(FIELD_INSTRUCTIONS)),
                    tools != null && tools.isArray() ? tools.size() : -1,
                    root.has(OpenAiNormalizerProfile.FIELD_PROMPT_CACHE_KEY),
                    root.has(FIELD_PROMPT_CACHE_RETENTION),
                    root.has(FIELD_PREVIOUS_RESPONSE_ID),
                    root.has(FIELD_CONVERSATION),
                    root.has(FIELD_METADATA),
                    scalarValue(root.get(OpenAiNormalizerProfile.FIELD_STORE)),
                    scalarValue(root.get(OpenAiNormalizerProfile.FIELD_STREAM))
            );
        }

        private static String scalarValue(JsonNode node) {
            if (node == null || node.isNull()) return "missing";
            if (node.isBoolean()) return String.valueOf(node.asBoolean());
            if (node.isTextual()) return "text";
            if (node.isNumber()) return "number";
            return node.getNodeType().name().toLowerCase();
        }

        private static String tailInputItems(JsonNode input) {
            if (input == null || !input.isArray() || input.isEmpty()) return "[]";
            int start = Math.max(0, input.size() - 8);
            List<String> items = new ArrayList<>();
            for (int i = start; i < input.size(); i++) {
                JsonNode item = input.get(i);
                int itemBytes = item.toString().getBytes(StandardCharsets.UTF_8).length;
                String role = item.path(FIELD_ROLE).asText("");
                String type = inputItemType(item);
                String itemHash = sha256Hex(item.toString(), Integer.MAX_VALUE);
                items.add(i + ":" + role + "/" + type + "/" + itemBytes + "B/" + itemHash);
            }
            return "[" + String.join(",", items) + "]";
        }

        private static String inputItemType(JsonNode item) {
            if (item == null || item.isNull()) return "null";
            String type = item.path(FIELD_TYPE).asText("");
            if (!type.isBlank()) return type;
            JsonNode content = item.get(FIELD_CONTENT);
            if (content == null || content.isNull()) return "no_content";
            if (content.isTextual()) return "text";
            if (content.isArray()) {
                List<String> partTypes = new ArrayList<>();
                for (JsonNode part : content) {
                    String partType = part.path(FIELD_TYPE).asText("");
                    if (!partType.isBlank() && !partTypes.contains(partType)) {
                        partTypes.add(partType);
                    }
                    if (partTypes.size() >= 3) break;
                }
                return partTypes.isEmpty() ? "content_array" : String.join("+", partTypes);
            }
            return content.getNodeType().name().toLowerCase();
        }
    }

    private static String sha256Hex(String value, int maxBytes) {
        if (value == null) return "null";
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        int length = Math.min(bytes.length, maxBytes);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(bytes, 0, length);
            byte[] hash = digest.digest();
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(value.hashCode());
        }
    }
}
