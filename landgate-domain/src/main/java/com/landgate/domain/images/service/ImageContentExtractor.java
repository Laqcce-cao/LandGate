package com.landgate.domain.images.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 图片内容提取器 —— 从各 AI 协议请求体中提取图片引用。
 * <p>
 * 支持协议：
 * <ul>
 *   <li>OpenAI Chat：images[].image_url.url</li>
 *   <li>Anthropic Messages：content[] 中 source.type=image 的 source.data</li>
 *   <li>Gemini：content[].parts[] 中 inlineData.data</li>
 *   <li>OpenAI Images：input images + mask</li>
 * </ul>
 * <p>
 * 提取的图片引用为 data URL (data:...) 或 http URL，用于内容审核等下游处理。
 */
@Slf4j
public final class ImageContentExtractor {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ImageContentExtractor() {
        // 工具类禁止实例化
    }

    /**
     * 从请求体中提取所有图片引用。
     *
     * @param requestBody 请求体 JSON 字符串
     * @return 去重后的图片 URL / data URL 列表
     */
    public static List<String> extractImages(String requestBody) {
        Set<String> images = new LinkedHashSet<>(); // 去重保序
        if (requestBody == null || requestBody.isEmpty()) {
            return List.of();
        }

        try {
            JsonNode body = MAPPER.readTree(requestBody);
            collectImages(body, images);
        } catch (Exception e) {
            log.debug("Failed to extract image content from request body", e);
        }
        return new ArrayList<>(images);
    }

    /**
     * 递归遍历 JSON 节点收集图片引用。
     */
    private static void collectImages(JsonNode node, Set<String> images) {
        if (node == null || node.isNull()) return;

        if (node.isArray()) {
            for (JsonNode child : node) {
                collectImages(child, images);
            }
            return;
        }

        if (!node.isObject()) return;

        // OpenAI Chat / Images 格式: image_url → url
        JsonNode imageUrl = node.get("image_url");
        if (imageUrl != null) {
            if (imageUrl.isTextual()) {
                addIfValid(images, imageUrl.asText());
            } else if (imageUrl.isObject()) {
                JsonNode url = imageUrl.get("url");
                if (url != null && url.isTextual()) {
                    addIfValid(images, url.asText());
                }
            }
        }

        // Anthropic Messages 格式: source.type=image → source.data
        JsonNode source = node.get("source");
        if (source != null && source.isObject()) {
            String sourceType = source.has("type") ? source.get("type").asText() : null;
            if ("image".equals(sourceType) || "base64".equals(sourceType)) {
                // 提取 data + media_type 构建 data URL
                JsonNode data = source.get("data");
                JsonNode mediaType = source.get("media_type");
                if (data != null && data.isTextual()) {
                    if (mediaType != null && mediaType.isTextual()) {
                        images.add("data:" + mediaType.asText() + ";base64," + data.asText());
                    } else {
                        images.add(data.asText());
                    }
                }
            }
        }

        // Gemini 格式: inlineData → mimeType + data
        JsonNode inlineData = node.get("inlineData");
        if (inlineData != null && inlineData.isObject()) {
            JsonNode data = inlineData.get("data");
            JsonNode mimeType = inlineData.get("mimeType");
            if (data != null && data.isTextual()) {
                if (mimeType != null && mimeType.isTextual()) {
                    images.add("data:" + mimeType.asText() + ";base64," + data.asText());
                } else {
                    images.add(data.asText());
                }
            }
        }
        // Gemini 驼峰变体: inline_data → mime_type + data
        JsonNode inlineDataSnake = node.get("inline_data");
        if (inlineDataSnake != null && inlineDataSnake.isObject()) {
            JsonNode data = inlineDataSnake.get("data");
            JsonNode mimeType = inlineDataSnake.get("mime_type");
            if (data != null && data.isTextual()) {
                if (mimeType != null && mimeType.isTextual()) {
                    images.add("data:" + mimeType.asText() + ";base64," + data.asText());
                } else {
                    images.add(data.asText());
                }
            }
        }

        // Gemini fileData 格式: fileData → fileUri
        JsonNode fileData = node.get("fileData");
        if (fileData != null && fileData.isObject()) {
            JsonNode fileUri = fileData.get("fileUri");
            if (fileUri != null && fileUri.isTextual()) {
                addIfValid(images, fileUri.asText());
            }
        }

        // 通用字段：直接是 data URL 或 HTTP URL 的字符串
        for (String field : new String[]{"url", "data", "base64"}) {
            JsonNode fieldNode = node.get(field);
            if (fieldNode != null && fieldNode.isTextual()) {
                addIfValid(images, fieldNode.asText());
            }
        }

        // 递归遍历所有子节点
        var fields = node.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            // 跳过已处理的特殊字段
            String key = entry.getKey();
            if ("image_url".equals(key) || "source".equals(key) || "inlineData".equals(key)
                    || "inline_data".equals(key) || "fileData".equals(key)
                    || "url".equals(key) || "data".equals(key) || "base64".equals(key)) {
                continue;
            }
            collectImages(entry.getValue(), images);
        }
    }

    /**
     * 仅添加有效的图片 URL（data: / http: / https: 前缀）。
     */
    private static void addIfValid(Set<String> images, String url) {
        if (url == null || url.isEmpty()) return;
        String lower = url.toLowerCase();
        if (lower.startsWith("data:") || lower.startsWith("http://") || lower.startsWith("https://")) {
            images.add(url);
        }
    }

    /**
     * 从 OpenAI Images 请求中提取用于内容审核的文本 + 图片。
     *
     * @param prompt 图片生成提示词
     * @param images 输入图片 URL / data URL 列表
     * @return "prompt: ... images: [...]" 格式的审核文本
     */
    public static String buildModerationText(String prompt, List<String> images) {
        StringBuilder sb = new StringBuilder();
        if (prompt != null && !prompt.isEmpty()) {
            sb.append("prompt: ").append(prompt);
        }
        if (images != null && !images.isEmpty()) {
            if (!sb.isEmpty()) sb.append(" ");
            sb.append("images: ").append(String.join(", ", images));
        }
        return sb.toString();
    }
}
