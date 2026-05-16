package com.landgate.infrastructure.dao.po;

import java.io.Serializable;
import java.util.Objects;

/**
 * AccountGroupPO 的复合主键类。
 * <p>
 * 用于在 MyBatis XML 中传递复合主键参数，也可用于 MapStruct 映射。
 * 保留 equals/hashCode 以支持集合操作。
 */
public class AccountGroupIdPO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long accountId;
    private Long groupId;

    public AccountGroupIdPO() {}

    public AccountGroupIdPO(Long accountId, Long groupId) {
        this.accountId = accountId;
        this.groupId = groupId;
    }

    public Long getAccountId() { return accountId; }
    public void setAccountId(Long accountId) { this.accountId = accountId; }
    public Long getGroupId() { return groupId; }
    public void setGroupId(Long groupId) { this.groupId = groupId; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AccountGroupIdPO that)) return false;
        return Objects.equals(accountId, that.accountId) && Objects.equals(groupId, that.groupId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(accountId, groupId);
    }
}
