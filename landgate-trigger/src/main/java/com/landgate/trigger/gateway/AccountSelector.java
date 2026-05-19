package com.landgate.trigger.gateway;

import com.landgate.domain.account.adapter.repository.IAccountRepository;
import com.landgate.domain.account.model.entity.AccountEntity;
import com.landgate.domain.group.adapter.repository.IAccountGroupRepository;
import com.landgate.domain.group.model.entity.AccountGroupEntity;
import com.landgate.domain.group.model.entity.GroupEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * 上游账号选择器 —— 按优先级从分组绑定的账号中选取可用账号。
 * <p>
 * 选择逻辑：遍历分组关联账号（按优先级排序）→ 跳过已删除/未激活/不可调度/被限流/过载的账号 →
 * 返回第一个可用账号。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AccountSelector {

    private final IAccountGroupRepository accountGroupRepository;
    private final IAccountRepository accountRepository;

    public AccountEntity getById(Long accountId) {
        if (accountId == null) return null;
        return accountRepository.findById(accountId)
                .filter(a -> a.getDeletedAt() == null)
                .filter(AccountEntity::isActive)
                .filter(AccountEntity::isSchedulable)
                .orElse(null);
    }

    public AccountEntity selectAccount(GroupEntity group) {
        if (group == null || group.getId() == null) {
            log.warn("Group is null or has no ID, cannot select account");
            return null;
        }

        List<AccountGroupEntity> links = accountGroupRepository.findByGroupIdOrderByPriority(group.getId());
        if (links.isEmpty()) {
            log.warn("No accounts bound to group: group_id={}", group.getId());
            return null;
        }

        log.debug("Selecting account for group: group_id={}, candidates={}", group.getId(), links.size());

        for (AccountGroupEntity link : links) {
            AccountEntity account = accountRepository.findById(link.getAccountId())
                    .filter(a -> a.getDeletedAt() == null)
                    .orElse(null);

            if (account == null) {
                log.debug("Account not found or deleted: account_id={}", link.getAccountId());
                continue;
            }
            if (!account.isActive()) {
                log.debug("Account not active: account_id={}, status={}", account.getId(), account.getStatus());
                continue;
            }
            if (!account.isSchedulable()) {
                log.debug("Account not schedulable: account_id={}", account.getId());
                continue;
            }
            if (account.isRateLimited()) {
                log.debug("Account rate-limited: account_id={}, reset_at={}", account.getId(), account.getRateLimitResetAt());
                continue;
            }
            if (account.isOverloaded()) {
                log.debug("Account overloaded: account_id={}, until={}", account.getId(), account.getOverloadUntil());
                continue;
            }

            log.info("Account selected: account_id={}, name={}, platform={}, priority={}",
                    account.getId(), account.getName(), account.getPlatform(), link.getPriority());
            return account;
        }

        log.warn("No available account for group: group_id={}", group.getId());
        return null;
    }

    public void updateLastUsed(Long accountId) {
        accountRepository.findById(accountId).ifPresent(a -> {
            a.setLastUsedAt(Instant.now());
            accountRepository.save(a);
        });
    }

    /**
     * 标记账号被上游限流，在冷却时间内不会被选中。
     *
     * @param accountId 账号 ID
     * @param resetAt   限流重置时间（通常为 now + Retry-After 秒数）
     */
    public void markRateLimited(Long accountId, Instant resetAt) {
        accountRepository.findById(accountId).ifPresent(a -> {
            a.setRateLimitedAt(Instant.now());
            a.setRateLimitResetAt(resetAt);
            accountRepository.save(a);
            log.info("Account rate-limited: id={}, name={}, reset_at={}", accountId, a.getName(), resetAt);
        });
    }

    /**
     * 标记账号过载，在冷却时间内不会被选中。
     *
     * @param accountId 账号 ID
     * @param until     过载截止时间
     */
    public void markOverloaded(Long accountId, Instant until) {
        accountRepository.findById(accountId).ifPresent(a -> {
            a.setOverloadUntil(until);
            accountRepository.save(a);
            log.info("Account overloaded: id={}, name={}, until={}", accountId, a.getName(), until);
        });
    }

    /**
     * 标记账号临时不可调度，在冷却时间内不会被选中。
     *
     * @param accountId 账号 ID
     * @param until     不可调度截止时间
     * @param reason    不可调度原因
     */
    public void markTempUnschedulable(Long accountId, Instant until, String reason) {
        accountRepository.findById(accountId).ifPresent(a -> {
            a.setTempUnschedulableUntil(until);
            a.setTempUnschedulableReason(reason);
            accountRepository.save(a);
            log.info("Account temp-unschedulable: id={}, name={}, until={}, reason={}",
                    accountId, a.getName(), until, reason);
        });
    }
}
