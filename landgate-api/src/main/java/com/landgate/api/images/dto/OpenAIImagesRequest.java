package com.landgate.api.images.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * OpenAI 图片 API 请求解析结果。
 * <p>
 * 对应 OpenAI Images API（/v1/images/generations、/v1/images/edits）的请求体结构。
 * 同时支持 JSON 和 multipart/form-data 两种 Content-Type。
 *
 * @see OpenAIImagesUpload
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OpenAIImagesRequest {

    /** 请求端点（generations 或 edits） */
    private String endpoint;

    /** 原始 Content-Type 头 */
    private String contentType;

    /** 是否为 multipart/form-data 请求 */
    @Builder.Default
    private boolean multipart = false;

    /** 图片生成模型名称（如 gpt-image-2） */
    private String model;

    /** 用户是否显式指定了 model 字段 */
    @Builder.Default
    private boolean explicitModel = false;

    /** 图片生成提示词 */
    private String prompt;

    /** 是否流式返回 */
    @Builder.Default
    private boolean stream = false;

    /** 生成图片数量，默认 1 */
    @Builder.Default
    private int n = 1;

    /** 图片尺寸（如 1024x1024、2048x2048） */
    private String size;

    /** 用户是否显式指定了 size 字段 */
    @Builder.Default
    private boolean explicitSize = false;

    /** 尺寸等级（1K / 2K / 4K），由 normalizeSizeTier() 计算 */
    private String sizeTier;

    /** 响应格式（url 或 b64_json） */
    private String responseFormat;

    /** 图片质量（standard / hd） */
    private String quality;

    /** 背景处理方式 */
    private String background;

    /** 输出图片格式（png / jpg / webp） */
    private String outputFormat;

    /** 内容审核级别 */
    private String moderation;

    /** 输入保真度 */
    private String inputFidelity;

    /** 风格 */
    private String style;

    /** 输出压缩级别 */
    private Integer outputCompression;

    /** 部分图片返回数量 */
    private Integer partialImages;

    /** 是否包含 mask 图片（仅 edits 端点） */
    @Builder.Default
    private boolean hasMask = false;

    /** 是否包含 OpenAI 原生选项参数 */
    @Builder.Default
    private boolean hasNativeOptions = false;

    /** 所需能力类型（Native = API Key 直连，Basic = OAuth/Codex 路径） */
    private String requiredCapability;

    /** 输入图片的 URL 列表 */
    @Builder.Default
    private List<String> inputImageURLs = List.of();

    /** mask 图片的 URL */
    private String maskImageURL;

    /** 上传的图片文件列表（仅 multipart 请求） */
    @Builder.Default
    private List<OpenAIImagesUpload> uploads = List.of();

    /** 上传的 mask 图片文件（仅 multipart 请求） */
    private OpenAIImagesUpload maskUpload;

    /** 原始请求体字节数组 */
    private byte[] body;
}
