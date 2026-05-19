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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 上游账号选择器 —— 负载感知的账户选择。
 * <p>
 * 选择逻辑：
 * <ol>
 *   <li>加载分组关联的全部候选账户</li>
 *   <li>过滤：跳过已删除/未激活/不可调度/被限流/过载的账户</li>
 *   <li>排序：优先级(高→低) → 负载率(低→高) → 最后使用时间(远→近)</li>
 *   <li>返回排序后的第一个账户</li>
 * </ol>
 * <p>
 * 负载率 = 当前活跃并发数 / (maxConcurrency × loadFactor/100)，
 * 实现同等优先级下负载均匀分摊。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AccountSelector {

    private final IAccountGroupRepository accountGroupRepository;
    private final IAccountRepository accountRepository;
    private final ConcurrencyService concurrencyService;

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

        // 加载全部候选账户并过滤不健康的
        List<Candidate> candidates = new ArrayList<>();
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

            double loadRate = calcLoadRate(account);
            candidates.add(new Candidate(account, link.getPriority(), loadRate));
        }

        if (candidates.isEmpty()) {
            log.warn("No available account for group: group_id={}", group.getId());
            return null;
        }

        // 排序：优先级(高→低) → 负载率(低→高) → 最后使用时间(远→近)
        candidates.sort(Comparator
                .comparingInt(Candidate::priority).reversed()
                .thenComparingDouble(Candidate::loadRate)
                .thenComparing(c -> c.account.getLastUsedAt() == null
                        ? Instant.EPOCH : c.account.getLastUsedAt()));

        AccountEntity selected = candidates.get(0).account;
        log.info("Account selected: account_id={}, name={}, platform={}, priority={}, load_rate={}",
                selected.getId(), selected.getName(), selected.getPlatform(),
                candidates.get(0).priority,
                String.format("%.2f", candidates.get(0).loadRate));
        return selected;
    }

    /**
     * 计算负载率 = 当前活跃并发数 / 有效并发上限。
     * <p>
     * 有效并发上限 = maxConcurrency × loadFactor / 100。
     * 负载率为 0 表示空闲，1.0 表示满载，> 1.0 表示超载。
     */
    private double calcLoadRate(AccountEntity account) {
        int active = concurrencyService.getActiveCount(account.getId());
        int max = account.getConcurrency();
        int loadFactor = account.getLoadFactor() != null ? account.getLoadFactor() : 100;
        int effectiveMax = max * loadFactor / 100;
        if (effectiveMax <= 0) return Double.MAX_VALUE;
        return (double) active / effectiveMax;
    }

    // ---- 内部候选记录 ----

    private record Candidate(AccountEntity account, int priority, double loadRate) {}

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
