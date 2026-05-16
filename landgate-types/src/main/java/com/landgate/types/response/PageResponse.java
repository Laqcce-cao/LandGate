package com.landgate.types.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 分页响应包装。
 *
 * @param <T> 列表元素类型
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PageResponse<T> {

    /** 数据列表 */
    private List<T> items;

    /** 当前页码 (1-based) */
    private int page;

    /** 每页数量 */
    private int size;

    /** 总记录数 */
    private long total;

    /** 总页数 */
    private int totalPages;

    public static <T> PageResponse<T> of(List<T> items, int page, int size, long total) {
        return PageResponse.<T>builder()
                .items(items)
                .page(page)
                .size(size)
                .total(total)
                .totalPages((int) Math.ceil((double) total / size))
                .build();
    }
}
