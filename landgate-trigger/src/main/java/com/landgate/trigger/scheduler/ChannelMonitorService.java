package com.landgate.trigger.scheduler;

import com.landgate.domain.account.adapter.repository.IAccountRepository;
import com.landgate.domain.account.model.entity.AccountEntity;
import com.landgate.types.enums.Platform;
import com.landgate.types.enums.Status;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 通道监控服务 —— 定期检查上游账号的可用性。
 * <p>
 * 每 60 秒对所有激活的可调度账号发起健康检查请求。
 * 连续失败 3 次后将账号标记为 ERROR 状态，恢复后自动重新激活。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChannelMonitorService {

    private final IAccountRepository accountRepository;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final ConcurrentHashMap<Long, AtomicInteger> failureCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Instant> lastCheckTimes = new ConcurrentHashMap<>();

    private static final int MAX_FAILURES = 3;
    private static final long CHECK_INTERVAL_MS = 60_000;

    @Scheduled(fixedDelay = CHECK_INTERVAL_MS)
    public void checkAllAccounts() {
        List<AccountEntity> activeAccounts = accountRepository.findAll();
        log.debug("Channel monitor checking {} accounts", activeAccounts.size());

        for (AccountEntity account : activeAccounts) {
            if (!account.isActive() || !account.isSchedulable()) {
                continue;
            }
            checkAccount(account);
        }
    }

    public void checkAccount(AccountEntity account) {
        String testUrl = getTestUrl(account);
        if (testUrl == null) {
            return;
        }

        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(testUrl))
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", "LandGate-ChannelMonitor/1.0")
                    .GET()
                    .build();

            HttpResponse<Void> resp = httpClient.send(req, HttpResponse.BodyHandlers.discarding());
            int status = resp.statusCode();

            if (status >= 200 && status < 500) {
                failureCounts.remove(account.getId());
                lastCheckTimes.put(account.getId(), Instant.now());

                if (Status.ERROR == account.getStatus()) {
                    account.setStatus(Status.ACTIVE);
                    account.setErrorMessage(null);
                    accountRepository.save(account);
                    log.info("Account recovered: id={}, name={}", account.getId(), account.getName());
                }
            } else {
                recordFailure(account, "HTTP " + status);
            }
        } catch (Exception e) {
            recordFailure(account, e.getMessage());
        }
    }

    private void recordFailure(AccountEntity account, String reason) {
        int failures = failureCounts
                .computeIfAbsent(account.getId(), k -> new AtomicInteger(0))
                .incrementAndGet();

        lastCheckTimes.put(account.getId(), Instant.now());

        log.warn("Account check failed: id={}, name={}, failures={}/{}, reason={}",
                account.getId(), account.getName(), failures, MAX_FAILURES, reason);

        if (failures >= MAX_FAILURES && Status.ERROR != account.getStatus()) {
            account.setStatus(Status.ERROR);
            account.setErrorMessage("Channel monitor: " + failures + " consecutive failures. Last: " + reason);
            accountRepository.save(account);
            log.error("Account marked as error: id={}, name={}, reason={}",
                    account.getId(), account.getName(), reason);
        }
    }

    private String getTestUrl(AccountEntity account) {
        Platform platform = account.getPlatform();
        if (platform == null) return null;

        return switch (platform) {
            case ANTHROPIC -> "https://api.anthropic.com/v1/messages";
            case OPENAI -> "https://api.openai.com/v1/models";
            case GEMINI -> "https://generativelanguage.googleapis.com/v1beta/models";
            default -> null;
        };
    }

    public Map<String, Object> getMonitorStatus() {
        return Map.of(
                "tracked_accounts", failureCounts.size(),
                "failure_counts", Map.copyOf(failureCounts),
                "last_checks", Map.copyOf(lastCheckTimes)
        );
    }

    public void resetFailureCount(Long accountId) {
        failureCounts.remove(accountId);
        log.info("Reset failure count for account: id={}", accountId);
    }
}
