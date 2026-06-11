package com.landgate.trigger.images;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.landgate.api.images.dto.OpenAIImagesRequest;
import com.landgate.api.images.dto.OpenAIImagesUpload;
import com.landgate.domain.account.model.entity.AccountEntity;
import com.landgate.infrastructure.upstream.HttpUpstreamClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 图片服务 —— 处理 OpenAI Images API 的请求解析、上游转发和响应处理。
 * <p>
 * 参考 sub2api 的 openai_images.go 实现，适配 LandGate Java 架构。
 * 支持 JSON 和 multipart/form-data 两种请求格式，API Key 直连转发到上游。
 * 图片数据仅在内存中保留，不持久化。
 * <p>
 * 上游端点：
 * <ul>
 *   <li>https://api.openai.com/v1/images/generations</li>
 *   <li>https://api.openai.com/v1/images/edits</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImagesService {

    private final HttpUpstreamClient httpUpstreamClient;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** OpenAI Images API 默认地址 */
    private static final String OPENAI_IMAGES_BASE = "https://api.openai.com";
    private static final String GENERATIONS_PATH = "/v1/images/generations";
    private static final String EDITS_PATH = "/v1/images/edits";

    /** 图片上传最大字节数：20MB */
    private static final int MAX_UPLOAD_PART_SIZE = 20 << 20;

    /** 上游请求超时时间 */
    private static final Duration UPSTREAM_TIMEOUT = Duration.ofSeconds(120);

    /** 默认图片生成模型 */
    private static final String DEFAULT_IMAGE_MODEL = "gpt-image-2";

    /** 原生选项参数名集合（非标准 form 字段，用于检测是否为 Native 请求） */
    private static final Set<String> NATIVE_OPTION_FIELDS = Set.of(
            "quality", "background", "output_format", "moderation",
            "input_fidelity", "style", "output_compression", "partial_images"
    );

    // ==================== 请求解析 ====================

    /**
     * 解析图片请求 —— 根据 Content-Type 分派 JSON 或 multipart 解析。
     *
     * @param body        原始请求体字节数组
     * @param contentType 请求 Content-Type 头
     * @param path        请求路径（用于判断 generations / edits）
     * @return 解析后的图片请求对象
     */
    public OpenAIImagesRequest parseImagesRequest(byte[] body, String contentType, String path) {
        String endpoint = normalizeEndpoint(path);

        if (isMultipart(contentType)) {
            log.debug("Parsing multipart image request: endpoint={}, size={} bytes", endpoint, body.length);
            OpenAIImagesRequest req = parseMultipartRequest(body, contentType, endpoint);
            applyDefaults(req);
            return req;
        } else {
            log.debug("Parsing JSON image request: endpoint={}, size={} bytes", endpoint, body.length);
            OpenAIImagesRequest req = parseJSONRequest(body, endpoint);
            applyDefaults(req);
            return req;
        }
    }

    /**
     * 判断 Content-Type 是否为 multipart/form-data。
     */
    private boolean isMultipart(String contentType) {
        return contentType != null && contentType.toLowerCase().startsWith("multipart/form-data");
    }

    /**
     * 标准化端点路径 —— 统一映射为 generations 或 edits。
     */
    private String normalizeEndpoint(String path) {
        if (path == null) return GENERATIONS_PATH;
        String lower = path.toLowerCase();
        if (lower.contains("/images/edits")) return EDITS_PATH;
        return GENERATIONS_PATH; // 默认 generations
    }

    // ==================== JSON 请求解析 ====================

    /**
     * 解析 JSON 格式的图片请求体。
     * <p>
     * 使用 Jackson 提取 model、prompt、stream、n、size 等字段。
     *
     * @param body     请求体字节数组
     * @param endpoint 目标端点
     * @return 解析后的请求对象
     */
    private OpenAIImagesRequest parseJSONRequest(byte[] body, String endpoint) {
        OpenAIImagesRequest.OpenAIImagesRequestBuilder builder = OpenAIImagesRequest.builder()
                .endpoint(endpoint)
                .contentType("application/json")
                .multipart(false)
                .body(body);

        try {
            JsonNode root = MAPPER.readTree(body);

            // 提取 model 字段（非空时设 explicitModel=true）
            if (root.has("model") && !root.get("model").isNull()) {
                builder.model(root.get("model").asText());
                builder.explicitModel(true);
            }

            // 提取 prompt 字段
            if (root.has("prompt") && !root.get("prompt").isNull()) {
                builder.prompt(root.get("prompt").asText());
            }

            // 提取 stream 字段（必须是 boolean 类型，避免 "true" 字符串误判）
            if (root.has("stream") && root.get("stream").isBoolean()) {
                builder.stream(root.get("stream").asBoolean());
            }

            // 提取 n 字段（生成图片数量）
            if (root.has("n") && root.get("n").isInt()) {
                int n = root.get("n").asInt();
                builder.n(n > 0 ? n : 1);
            }

            // 提取 size 字段
            if (root.has("size") && !root.get("size").isNull()) {
                String size = root.get("size").asText().trim();
                builder.size(size);
                builder.explicitSize(true);
                builder.sizeTier(normalizeSizeTier(size));
            }

            // 提取其他可选字段
            builder.responseFormat(getTextOrNull(root, "response_format"));
            builder.quality(getTextOrNull(root, "quality"));
            builder.background(getTextOrNull(root, "background"));
            builder.outputFormat(getTextOrNull(root, "output_format"));
            builder.moderation(getTextOrNull(root, "moderation"));
            builder.inputFidelity(getTextOrNull(root, "input_fidelity"));
            builder.style(getTextOrNull(root, "style"));

            // 提取 output_compression（整数）
            if (root.has("output_compression") && root.get("output_compression").isInt()) {
                builder.outputCompression(root.get("output_compression").asInt());
            }

            // 提取 partial_images（整数）
            if (root.has("partial_images") && root.get("partial_images").isInt()) {
                builder.partialImages(root.get("partial_images").asInt());
            }

            // 检测 mask 字段是否存在
            if (root.has("mask") && !root.get("mask").isNull()) {
                builder.hasMask(true);
            }

            // 检测原生选项参数
            boolean hasNative = false;
            for (String field : NATIVE_OPTION_FIELDS) {
                if (root.has(field) && !root.get(field).isNull()) {
                    hasNative = true;
                    break;
                }
            }
            builder.hasNativeOptions(hasNative);

            // 提取输入图片 URL（仅 edits 端点）
            if (root.has("images") && root.get("images").isArray()) {
                List<String> urls = new ArrayList<>();
                for (JsonNode img : root.get("images")) {
                    if (img.has("image_url") && !img.get("image_url").isNull()) {
                        urls.add(img.get("image_url").asText());
                    }
                }
                builder.inputImageURLs(urls);
            }

            // 提取 mask URL
            if (root.has("mask") && root.get("mask").isObject()) {
                JsonNode maskUrl = root.get("mask").get("image_url");
                if (maskUrl != null && !maskUrl.isNull()) {
                    builder.maskImageURL(maskUrl.asText());
                }
            }

        } catch (Exception e) {
            log.warn("Failed to parse JSON image request", e);
        }

        return builder.build();
    }

    /**
     * 安全获取 JSON 文本字段，不存在或为 null 时返回 null。
     */
    private String getTextOrNull(JsonNode node, String field) {
        if (node.has(field) && !node.get(field).isNull()) {
            return node.get(field).asText();
        }
        return null;
    }

    // ==================== Multipart 请求解析 ====================

    /**
     * 解析 multipart/form-data 格式的图片请求体。
     * <p>
     * 手动解析 boundary，提取文件部分为 {@link OpenAIImagesUpload}，
     * 表单字段部分作为文本参数。
     *
     * @param body        原始请求体字节数组
     * @param contentType 完整 Content-Type 头（含 boundary）
     * @param endpoint    目标端点
     * @return 解析后的请求对象
     */
    private OpenAIImagesRequest parseMultipartRequest(byte[] body, String contentType, String endpoint) {
        OpenAIImagesRequest.OpenAIImagesRequestBuilder builder = OpenAIImagesRequest.builder()
                .endpoint(endpoint)
                .contentType(contentType)
                .multipart(true)
                .body(body);

        String boundary = extractBoundary(contentType);
        if (boundary == null) {
            log.warn("Cannot extract boundary from Content-Type: {}", contentType);
            return builder.build();
        }

        try {
            // 手动解析 multipart body
            List<OpenAIImagesUpload> uploads = new ArrayList<>();
            OpenAIImagesUpload maskUpload = null;

            byte[] boundaryBytes = ("--" + boundary).getBytes(StandardCharsets.UTF_8);
            byte[] endBoundaryBytes = ("--" + boundary + "--").getBytes(StandardCharsets.UTF_8);
            byte[] crlf = "\r\n".getBytes(StandardCharsets.UTF_8);
            byte[] headerEnd = "\r\n\r\n".getBytes(StandardCharsets.UTF_8);

            int pos = indexOf(body, boundaryBytes, 0);
            if (pos < 0) {
                log.warn("Boundary not found in multipart body");
                return builder.build();
            }

            while (pos < body.length) {
                int nextBoundary = indexOf(body, boundaryBytes, pos + boundaryBytes.length);
                if (nextBoundary < 0) {
                    // 检查结束边界
                    int endPos = indexOf(body, endBoundaryBytes, pos);
                    if (endPos >= 0) break;
                    break;
                }

                // 跳过 boundary + CRLF
                int partStart = pos + boundaryBytes.length;
                // 跳过可能的 CRLF
                if (partStart + crlf.length <= body.length
                        && Arrays.equals(Arrays.copyOfRange(body, partStart, partStart + crlf.length), crlf)) {
                    partStart += crlf.length;
                }

                int partEnd = nextBoundary;
                // 回退 CRLF（boundary 前的换行符）
                if (partEnd >= crlf.length) {
                    byte[] beforeBoundary = Arrays.copyOfRange(body, partEnd - crlf.length, partEnd);
                    if (Arrays.equals(beforeBoundary, crlf)) {
                        partEnd -= crlf.length;
                    }
                }

                // 解析 part 头部和内容
                if (partEnd > partStart) {
                    byte[] part = Arrays.copyOfRange(body, partStart, partEnd);
                    int headerEndIdx = indexOf(part, headerEnd, 0);
                    if (headerEndIdx > 0) {
                        String headers = new String(Arrays.copyOfRange(part, 0, headerEndIdx), StandardCharsets.UTF_8);
                        byte[] content = Arrays.copyOfRange(part, headerEndIdx + headerEnd.length, part.length);

                        String fieldName = extractHeaderParam(headers, "name");
                        String fileName = extractHeaderParam(headers, "filename");

                        if (fileName != null && !fileName.isEmpty()) {
                            // 文件 part
                            String fileContentType = extractHeaderValue(headers, "Content-Type");
                            OpenAIImagesUpload upload = OpenAIImagesUpload.builder()
                                    .fieldName(fieldName)
                                    .fileName(fileName)
                                    .contentType(fileContentType)
                                    .data(content)
                                    .build();

                            if ("mask".equals(fieldName)) {
                                maskUpload = upload;
                            } else {
                                // "image" 或 "image[N]" 字段
                                // 限制单个文件大小 20MB
                                if (content.length <= MAX_UPLOAD_PART_SIZE) {
                                    uploads.add(upload);
                                } else {
                                    log.warn("Upload part exceeds size limit: field={}, size={}", fieldName, content.length);
                                }
                            }
                        } else if (fieldName != null) {
                            // 普通表单字段
                            String value = new String(content, StandardCharsets.UTF_8).trim();
                            switch (fieldName) {
                                case "model":
                                    builder.model(value);
                                    builder.explicitModel(true);
                                    break;
                                case "prompt":
                                    builder.prompt(value);
                                    break;
                                case "stream":
                                    builder.stream("true".equalsIgnoreCase(value));
                                    break;
                                case "n":
                                    try {
                                        int n = Integer.parseInt(value);
                                        builder.n(n > 0 ? n : 1);
                                    } catch (NumberFormatException ignored) {}
                                    break;
                                case "size":
                                    builder.size(value);
                                    builder.explicitSize(true);
                                    builder.sizeTier(normalizeSizeTier(value));
                                    break;
                                case "response_format":
                                    builder.responseFormat(value);
                                    break;
                                case "quality":
                                    builder.quality(value);
                                    break;
                                case "background":
                                    builder.background(value);
                                    break;
                                case "output_format":
                                    builder.outputFormat(value);
                                    break;
                                case "moderation":
                                    builder.moderation(value);
                                    break;
                                case "input_fidelity":
                                    builder.inputFidelity(value);
                                    break;
                                case "style":
                                    builder.style(value);
                                    break;
                                case "output_compression":
                                    try {
                                        builder.outputCompression(Integer.parseInt(value));
                                    } catch (NumberFormatException ignored) {}
                                    break;
                                case "partial_images":
                                    try {
                                        builder.partialImages(Integer.parseInt(value));
                                    } catch (NumberFormatException ignored) {}
                                    break;
                                // 未知字段检查是否为原生选项
                                default:
                                    if (NATIVE_OPTION_FIELDS.contains(fieldName)) {
                                        builder.hasNativeOptions(true);
                                    }
                                    break;
                            }
                        }
                    }
                }

                pos = nextBoundary;
            }

            builder.uploads(uploads);
            if (maskUpload != null) {
                builder.maskUpload(maskUpload);
                builder.hasMask(true);
            }

        } catch (Exception e) {
            log.error("Failed to parse multipart image request", e);
        }

        return builder.build();
    }

    /**
     * 从 Content-Type 头中提取 boundary 参数。
     */
    private String extractBoundary(String contentType) {
        if (contentType == null) return null;
        int idx = contentType.indexOf("boundary=");
        if (idx < 0) return null;
        String boundary = contentType.substring(idx + 9);
        // 去除可能的引号
        if (boundary.startsWith("\"") && boundary.endsWith("\"")) {
            boundary = boundary.substring(1, boundary.length() - 1);
        }
        // 去除后续参数
        int semicolon = boundary.indexOf(";");
        if (semicolon > 0) {
            boundary = boundary.substring(0, semicolon);
        }
        return boundary.trim();
    }

    /**
     * 从 multipart part 头中提取参数值（如 name、filename）。
     */
    private String extractHeaderParam(String headers, String paramName) {
        // 匹配 name="value" 或 name=value
        Pattern pattern = Pattern.compile(
                paramName + "\\s*=\\s*\"([^\"]*)\"|" + paramName + "\\s*=\\s*([^;\\r\\n]+)",
                Pattern.CASE_INSENSITIVE);
        Matcher m = pattern.matcher(headers);
        if (m.find()) {
            return m.group(1) != null ? m.group(1) : m.group(2);
        }
        return null;
    }

    /**
     * 从 multipart part 头中提取单个 header 值（如 Content-Type）。
     */
    private String extractHeaderValue(String headers, String headerName) {
        Pattern pattern = Pattern.compile(
                headerName + "\\s*:\\s*([^\\r\\n]+)",
                Pattern.CASE_INSENSITIVE);
        Matcher m = pattern.matcher(headers);
        if (m.find()) {
            return m.group(1).trim();
        }
        return null;
    }

    /**
     * 查找子数组在父数组中的起始索引（Boyer-Moore 简化版）。
     */
    private int indexOf(byte[] haystack, byte[] needle, int fromIndex) {
        if (needle.length == 0) return fromIndex;
        if (fromIndex + needle.length > haystack.length) return -1;
        for (int i = fromIndex; i <= haystack.length - needle.length; i++) {
            boolean found = true;
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    found = false;
                    break;
                }
            }
            if (found) return i;
        }
        return -1;
    }

    // ==================== 默认值填充 ====================

    /**
     * 设置默认值：n 默认为 1，model 默认为 gpt-image-2。
     */
    private void applyDefaults(OpenAIImagesRequest req) {
        if (req.getN() <= 0) {
            req.setN(1);
        }
        if (req.getModel() == null || req.getModel().isEmpty()) {
            req.setModel(DEFAULT_IMAGE_MODEL);
        }
        if (req.getSizeTier() == null) {
            req.setSizeTier(normalizeSizeTier(req.getSize()));
        }
    }

    // ==================== 尺寸分级 ====================

    /**
     * 将 OpenAI 图片尺寸字符串映射为计费等级。
     * <p>
     * 映射规则（参考 sub2api normalizeOpenAIImageSizeTier）：
     * <ul>
     *   <li>"" 或 "auto" → "2K"（默认）</li>
     *   <li>"1024x1024" → "1K"</li>
     *   <li>"1536x1024"、"1792x1024"、"2048x2048" 等 → "2K"</li>
     *   <li>"3840x2160"、"2160x3840" → "4K"</li>
     * </ul>
     *
     * @param size 尺寸字符串（如 "1024x1024"）
     * @return 尺寸等级（"1K" / "2K" / "4K"）
     */
    public String normalizeSizeTier(String size) {
        if (size == null || size.isEmpty() || "auto".equalsIgnoreCase(size)) {
            return "2K"; // 默认中等尺寸
        }

        String lower = size.toLowerCase().trim();

        // 精确匹配已知尺寸
        return switch (lower) {
            case "1024x1024" -> "1K";
            case "1536x1024", "1792x1024", "2048x2048",
                 "1024x1536", "1024x1792",
                 "1440x2560", "2560x1440" -> "2K";
            case "3840x2160", "2160x3840" -> "4K";
            default -> {
                // 未知尺寸：按像素面积估算
                String[] parts = lower.split("x");
                if (parts.length == 2) {
                    try {
                        int w = Integer.parseInt(parts[0].trim());
                        int h = Integer.parseInt(parts[1].trim());
                        if (w * h > 2560 * 1440) yield "4K";
                        if (w * h > 1024 * 1024) yield "2K";
                    } catch (NumberFormatException e) {
                        // 解析失败，返回默认
                    }
                }
                yield "2K"; // 默认
            }
        };
    }

    // ==================== Capability 分类 ====================

    /**
     * 判断图片请求是否需要走 Native (API Key 直连) 还是 Basic (OAuth/Codex) 路径。
     * <p>
     * Native 条件（满足任一即走 API Key 直连）：
     * <ul>
     *   <li>用户显式指定了 model</li>
     *   <li>用户显式指定了 size</li>
     *   <li>stream = true</li>
     *   <li>n != 1</li>
     *   <li>包含 mask</li>
     *   <li>包含原生选项参数</li>
     *   <li>edits 端点 + 非 multipart</li>
     *   <li>response_format 设置了且不是 b64_json</li>
     * </ul>
     * 否则走 Basic 路径。
     *
     * @param req 解析后的请求
     * @return "images-native" 或 "images-basic"
     */
    public String classifyCapability(OpenAIImagesRequest req) {
        if (req.isExplicitModel()) return "images-native";
        if (req.isExplicitSize()) return "images-native";
        if (req.getModel() != null && !req.getModel().startsWith("gpt-image-")) return "images-native";
        if (req.isStream()) return "images-native";
        if (req.getN() != 1) return "images-native";
        if (req.isHasMask()) return "images-native";
        if (req.isHasNativeOptions()) return "images-native";
        if (req.getEndpoint() != null && req.getEndpoint().contains("edits")
                && !req.isMultipart()) return "images-native";
        if (req.getResponseFormat() != null && !"b64_json".equals(req.getResponseFormat())) return "images-native";

        return "images-basic";
    }

    // ==================== 上游转发 ====================

    /**
     * 转发图片请求到上游 OpenAI API。
     * <p>
     * 当前实现 API Key 直连路径（Native capability）。
     *
     * @param account     上游账号
     * @param parsed      解析后的请求
     * @param accessToken 访问令牌
     * @return 上游 HTTP 响应
     * @throws IOException          网络异常
     * @throws InterruptedException 线程中断
     */
    public HttpResponse<InputStream> forwardImages(AccountEntity account,
                                                     OpenAIImagesRequest parsed,
                                                     String accessToken)
            throws IOException, InterruptedException {
        return forwardAPIKey(account, parsed, accessToken);
    }

    /**
     * API Key 路径 —— 直接转发到 OpenAI Images API。
     * <p>
     * 对于 JSON 请求：body 以 application/json 转发。
     * 对于 multipart 请求：body 以原始 multipart/form-data 转发（带 boundary）。
     *
     * @param account     上游账号
     * @param parsed      解析后的请求
     * @param accessToken 访问令牌
     * @return 上游 HTTP 响应
     * @throws IOException          网络异常
     * @throws InterruptedException 线程中断
     */
    private HttpResponse<InputStream> forwardAPIKey(AccountEntity account,
                                                      OpenAIImagesRequest parsed,
                                                      String accessToken)
            throws IOException, InterruptedException {
        // 确定上游 URL（优先使用账号自定义 base_url）
        String baseUrl = getAccountBaseUrl(account);
        String path = parsed.getEndpoint() != null ? parsed.getEndpoint() : GENERATIONS_PATH;
        URI uri = URI.create(baseUrl + path);

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(UPSTREAM_TIMEOUT)
                .header("Authorization", "Bearer " + accessToken);

        byte[] body = parsed.getBody();
        if (parsed.isMultipart()) {
            // Multipart 请求：保持原始 Content-Type 和 body
            requestBuilder.header("Content-Type", parsed.getContentType())
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body != null ? body : new byte[0]));
            log.debug("Forwarding multipart image request: url={}, content_type={}, size={}",
                    uri, parsed.getContentType(), body != null ? body.length : 0);
        } else {
            // JSON 请求：application/json
            requestBuilder.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body != null ? body : new byte[0]));
            log.debug("Forwarding JSON image request: url={}, size={}",
                    uri, body != null ? body.length : 0);
        }

        HttpRequest upstreamReq = requestBuilder.build();
        return httpUpstreamClient.send(upstreamReq);
    }

    /**
     * 从账号的 extra 字段提取自定义 base_url，否则使用默认 OpenAI API 地址。
     */
    private String getAccountBaseUrl(AccountEntity account) {
        if (account != null && account.getExtra() != null && !account.getExtra().isEmpty()) {
            try {
                JsonNode extra = MAPPER.readTree(account.getExtra());
                if (extra.has("base_url") && !extra.get("base_url").isNull()) {
                    String baseUrl = extra.get("base_url").asText();
                    // 去掉尾部斜杠
                    if (baseUrl.endsWith("/")) {
                        baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
                    }
                    return baseUrl;
                }
            } catch (Exception e) {
                log.debug("Failed to parse account extra for base_url: account_id={}", account.getId());
            }
        }
        return OPENAI_IMAGES_BASE;
    }

    // ==================== 响应处理 ====================

    /**
     * 处理非流式图片响应 —— 将上游响应体直接写入客户端响应。
     *
     * @param upstreamResp 上游 HTTP 响应
     * @param outputStream 客户端响应输出流
     * @return 响应体字节数组（用于后续提取图片数量）
     * @throws IOException IO 异常
     */
    public byte[] handleNonStreamingResponse(HttpResponse<InputStream> upstreamResp,
                                              OutputStream outputStream) throws IOException {
        byte[] body;
        try (InputStream in = upstreamResp.body()) {
            body = in.readAllBytes();
        }

        // 将上游响应体写入客户端响应
        outputStream.write(body);
        outputStream.flush();
        log.debug("Non-streaming image response: status={}, size={} bytes",
                upstreamResp.statusCode(), body.length);
        return body;
    }

    /**
     * 处理流式图片响应（SSE） —— 逐行透传上游 SSE 事件到客户端。
     * <p>
     * 同时累积 SSE data 行，用于在流结束后提取图片数量。
     *
     * @param upstreamResp 上游 HTTP 响应
     * @param outputStream 客户端响应输出流
     * @return 累积的 SSE data 行内容（用于图片计数）
     * @throws IOException IO 异常
     */
    public String handleStreamingResponse(HttpResponse<InputStream> upstreamResp,
                                           OutputStream outputStream) throws IOException {
        StringBuilder sseDataAccumulator = new StringBuilder();

        try (InputStream in = upstreamResp.body();
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {

            String line;
            while ((line = reader.readLine()) != null) {
                // 写入客户端（SSE 格式：每行以 \n 结束）
                outputStream.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                outputStream.flush();

                // 累积 data 行用于后续图片计数
                if (line.startsWith("data: ")) {
                    String data = line.substring(6).trim();
                    if (!"[DONE]".equals(data) && !data.isEmpty()) {
                        if (!sseDataAccumulator.isEmpty()) {
                            sseDataAccumulator.append("\n");
                        }
                        sseDataAccumulator.append(data);
                    }
                }
            }
        }

        log.debug("Streaming image response completed: status={}, sse_data_length={}",
                upstreamResp.statusCode(), sseDataAccumulator.length());
        return sseDataAccumulator.toString();
    }

    // ==================== 图片计数 ====================

    /**
     * 从 OpenAI Images API 响应中提取生成的图片数量。
     * <p>
     * 查找 data[] 数组中的图片对象（含 b64_json 或 url 字段）。
     *
     * @param responseBody 响应体 JSON 字符串（流式请求为累积的 SSE data）
     * @return 图片数量，提取失败返回 0
     */
    public int extractImageCount(String responseBody) {
        if (responseBody == null || responseBody.isEmpty()) return 0;

        try {
            JsonNode root = MAPPER.readTree(responseBody);

            // data[] 数组是 OpenAI Images API 返回图片的标准格式
            if (root.has("data") && root.get("data").isArray()) {
                JsonNode dataArr = root.get("data");
                int count = 0;
                for (JsonNode item : dataArr) {
                    if (item.has("b64_json") || item.has("url")) {
                        count++;
                    }
                }
                if (count > 0) return count;
                return dataArr.size(); // 无 b64_json/url 时，数组长度即为图片数
            }

            // 流式响应中可能以单条 SSE 事件返回单张图片
            if (root.has("b64_json") || root.has("url")) {
                return 1;
            }
        } catch (Exception e) {
            log.debug("Failed to extract image count from response body", e);
        }
        return 0;
    }
}
