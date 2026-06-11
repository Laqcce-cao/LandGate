package com.landgate.infrastructure.dao.po;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 持久化对象基类 —— 提供时间戳和软删除字段。
 * <p>
 * 所有使用软删除的 PO 均应继承此类。
 * 字段由 MyBatis XML mapper 中的 SQL 显式维护（insert/update 时设置 timestamp）。
 *
 * <table>
 *   <tr><th>column</th><th>type</th><th>desc</th></tr>
 *   <tr><td>created_at</td><td>datetime</td><td>创建时间</td></tr>
 *   <tr><td>updated_at</td><td>datetime</td><td>最后更新时间</td></tr>
 *   <tr><td>deleted_at</td><td>datetime</td><td>软删除时间，NULL 表示未删除</td></tr>
 * </table>
 */
@Getter
@Setter
public abstract class BasePO {

    /** 创建时间 */
    private Instant createdAt;

    /** 最后更新时间 */
    private Instant updatedAt;

    /** 软删除时间（NULL = 未删除） */
    private Instant deletedAt;

    /** 判断记录是否已被软删除 */
    public boolean isDeleted() {
        return deletedAt != null;
    }
}
