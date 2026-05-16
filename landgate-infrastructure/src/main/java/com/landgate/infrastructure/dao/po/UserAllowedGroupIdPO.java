package com.landgate.infrastructure.dao.po;

import java.io.Serializable;
import java.util.Objects;

/**
 * UserAllowedGroupPO 的复合主键类。
 * <p>
 * 用于在 MyBatis XML 中传递复合主键参数。
 * 保留 equals/hashCode 以支持集合操作。
 */
public class UserAllowedGroupIdPO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long userId;
    private Long groupId;

    public UserAllowedGroupIdPO() {}

    public UserAllowedGroupIdPO(Long userId, Long groupId) {
        this.userId = userId;
        this.groupId = groupId;
    }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getGroupId() { return groupId; }
    public void setGroupId(Long groupId) { this.groupId = groupId; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof UserAllowedGroupIdPO that)) return false;
        return Objects.equals(userId, that.userId) && Objects.equals(groupId, that.groupId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, groupId);
    }
}
