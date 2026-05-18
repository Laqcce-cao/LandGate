package com.landgate.trigger.scheduler;

import com.landgate.domain.account.model.entity.AccountEntity;
import com.landgate.domain.account.adapter.repository.IAccountRepository;
import com.landgate.trigger.gateway.OAuthTokenRefreshService;
import com.landgate.types.constant.RedisKeys;
import com.landgate.types.enums.AccountType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collection;

/**
 * OAuth Token 主动刷新调度器 —— 定期扫描 Redis Sorted Set，提前刷新即将过期的 token。
 * <p>
 * 每 30 秒扫描一次，查找在未来 5 分钟内过期的 token 并触发刷新。
 * Token 的过期时间作为 Sorted Set 的 score，account_id 作为 member。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OAuthTokenRefreshScheduler {

    private final OAuthTokenRefreshService tokenRefreshService;
    private final IAccountRepository accountRepository;

    @Qualifier("redissonClient")
    private final RedissonClient redissonClient;

    /** 提前刷新窗口：在未来 5 分钟内过期的 token 会被提前刷新 */
    private static final long REFRESH_WINDOW_SECONDS = 5 * 60;

    /**
     * 定时扫描 Redis Sorted Set，对未来 5 分钟内过期的 OAuth token 执行主动刷新。
     * <p>
     * 每 30 秒执行一次。在刷新前会校验账号仍然存在且为 OAUTH 类型，
     * 已删除或类型变更的账号会被自动从 Sorted Set 中清理。
     */
    @Scheduled(fixedDelay = 30_000)
    public void proactiveRefresh() {
        RScoredSortedSet<String> set = redissonClient.getScoredSortedSet(
                RedisKeys.OAUTH_TOKEN_EXPIRY_KEY);

        long now = Instant.now().getEpochSecond();
        long threshold = now + REFRESH_WINDOW_SECONDS;

        // Fetch account IDs whose tokens expire within the refresh window
        Collection<String> accountIds = set.valueRange(0, true, threshold, true);

        if (accountIds.isEmpty()) return;

        log.debug("Proactive OAuth refresh: {} accounts expiring within {}s", accountIds.size(), REFRESH_WINDOW_SECONDS);

        for (String accountIdStr : accountIds) {
            try {
                Long accountId = Long.parseLong(accountIdStr);

                // Verify account still exists and is OAuth type
                AccountEntity account = accountRepository.findById(accountId).orElse(null);
                if (account == null || account.getDeletedAt() != null
                        || account.getType() != AccountType.OAUTH) {
                    tokenRefreshService.removeProactiveRefresh(accountId);
                    continue;
                }

                String newToken = tokenRefreshService.refreshAccessToken(accountId);
                if (newToken != null) {
                    log.info("Proactive OAuth token refreshed: account_id={}", accountId);
                }
            } catch (NumberFormatException e) {
                set.remove(accountIdStr);
            } catch (Exception e) {
                log.error("Proactive OAuth refresh error: account_id={}", accountIdStr, e);
            }
        }
    }
}
