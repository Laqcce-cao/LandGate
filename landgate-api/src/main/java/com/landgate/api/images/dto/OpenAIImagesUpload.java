package com.landgate.api.images.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 图片上传数据 —— 从 multipart/form-data 请求中解析的单个文件。
 * <p>
 * 图片数据仅在请求生命周期内存中保留，不持久化到磁盘或数据库。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OpenAIImagesUpload {

    /** multipart 表单字段名（如 "image"、"image[0]"） */
    private String fieldName;

    /** 上传时的原始文件名 */
    private String fileName;

    /** 文件的 MIME 类型（如 image/png） */
    private String contentType;

    /** 文件的原始字节数据，仅内存中保留 */
    private byte[] data;

    /** 图片宽度（像素），当前为占位值，后续实现解析 */
    @Builder.Default
    private int width = 0;

    /** 图片高度（像素），当前为占位值，后续实现解析 */
    @Builder.Default
    private int height = 0;
}
