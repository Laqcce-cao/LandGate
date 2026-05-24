package com.landgate.trigger.gateway;

/**
 * 并发槽位 —— 持有 RPermitExpirableSemaphore 返回的 permitId 和对应的 accountId。
 * <p>
 * tryAcquire 成功时返回实例，失败时返回 null。
 * release 时直接从 slot 中读取 accountId，调用方无需额外传参。
 */
public class ConcurrencySlot {

    private final String permitId;
    private final Long accountId;

    private ConcurrencySlot(String permitId, Long accountId) {
        this.permitId = permitId;
        this.accountId = accountId;
    }

    /** 创建已获取的槽位 */
    public static ConcurrencySlot of(String permitId, Long accountId) {
        return new ConcurrencySlot(permitId, accountId);
    }

    public String getPermitId() {
        return permitId;
    }

    public Long getAccountId() {
        return accountId;
    }

    /** permitId 非空即为已获取 */
    public boolean isAcquired() {
        return permitId != null;
    }
}
