package com.landgate.domain.images.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.landgate.domain.group.model.entity.GroupEntity;
import lombok.extern.slf4j.Slf4j;

/**
 * 图片生成意图检测工具类。
 * <p>
 * <b>核心原则：不硬编码模型名。</b>
 * 新模型层出不穷（gpt-5.5、deepseek-v4-pro 等都能识别图片），靠名字匹配永远追不上。
 * <p>
 * 判断方式分三个维度，优先级从高到低：
 * <ol>
 *   <li><b>端点路径</b>：/images/* 端点 → 确定是图片生成请求</li>
 *   <li><b>请求内容</b>：tools[] 含 image_generation → 确定是图片生成请求</li>
 *   <li><b>模型名前缀</b>（兜底）：gpt-image-* 前缀 → 辅助判断</li>
 * </ol>
 * <p>
 * <b>关键区分</b>：多模态 chat 模型（gpt-5.5、deepseek-v4-pro 等）虽然能"识别图片"，
 * 但它们是正常的 chat 请求 —— 图片作为输入是 base64 嵌在 JSON body 里的，
 * 走 /v1/chat/completions 端点，token 计费。不需要 /images/* 端点，不需要图片计费。
 */
@Slf4j
public final class ImageGenerationIntent {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ImageGenerationIntent() {
        // 工具类禁止实例化
    }

    /**
     * 判断是否为图片生成端点。
     * <p>
     * 匹配 /images/generations、/images/edits 及其变体
     * （/v1/images/generations、/images/edits 等）。
     *
     * @param path 请求路径
     * @return true 表示该端点用于图片生成
     */
    public static boolean isImageGenerationEndpoint(String path) {
        if (path == null) return false;
        String lower = path.toLowerCase();
        return lower.contains("/images/generations") || lower.contains("/images/edits");
    }

    /**
     * 综合判断请求是否为图片生成意图。
     * <p>
     * 先看端点路径，再看请求体中的工具调用。
     *
     * @param path        请求路径
     * @param requestBody 请求体 JSON 字符串（可为 null）
     * @return true 表示该请求意图为图片生成
     */
    public static boolean isImageGenerationIntent(String path, String requestBody) {
        // 维度 A：端点路径判断（最可靠）
        if (isImageGenerationEndpoint(path)) {
            return true;
        }

        // 维度 B：请求体中包含 image_generation 工具调用
        if (requestBody != null && !requestBody.isEmpty()) {
            try {
                JsonNode body = MAPPER.readTree(requestBody);
                if (hasImageGenerationTool(body.get("tools"))) return true;
                if (isImageGenerationToolChoice(body.get("tool_choice"))) return true;
            } catch (Exception e) {
                log.debug("Failed to parse request body for image intent detection", e);
            }
        }
        return false;
    }

    /**
     * 检查 tools 数组中是否包含 type=image_generation 的工具。
     */
    private static boolean hasImageGenerationTool(JsonNode tools) {
        if (tools == null || !tools.isArray()) return false;
        for (JsonNode tool : tools) {
            JsonNode type = tool.get("type");
            if (type != null && "image_generation".equals(type.asText())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 检查 tool_choice 是否选择了 image_generation 工具。
     */
    private static boolean isImageGenerationToolChoice(JsonNode toolChoice) {
        if (toolChoice == null) return false;
        // 格式为 {"type": "image_generation"} 或字符串 "image_generation"
        if (toolChoice.isTextual()) {
            return "image_generation".equals(toolChoice.asText());
        }
        JsonNode type = toolChoice.get("type");
        return type != null && "image_generation".equals(type.asText());
    }

    /**
     * 辅助方法 —— 检查模型名是否为明确的图片生成模型。
     * <p>
     * 仅匹配 gpt-image-* 前缀（OpenAI 明确的图片生成模型系列）。
     * 不匹配 gpt-5.5、deepseek 等多模态 chat 模型。
     *
     * @param model 模型名称
     * @return true 表示该模型是图片生成专用模型
     */
    public static boolean isImageGenerationModel(String model) {
        return model != null && (model.startsWith("gpt-image-")
                || model.startsWith("dall-e"));
    }

    /**
     * 检查分组是否允许图片生成。
     *
     * @param group 分组实体
     * @return true 表示该分组允许使用图片生成功能
     */
    public static boolean groupAllowsImageGeneration(GroupEntity group) {
        return group != null && Boolean.TRUE.equals(group.getAllowImageGeneration());
    }
}
