package com.landgate.trigger.gateway.account;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.landgate.domain.account.adapter.repository.IAccountRepository;
import com.landgate.domain.account.model.entity.AccountEntity;
import com.landgate.domain.group.adapter.repository.IAccountGroupRepository;
import com.landgate.domain.group.model.entity.AccountGroupEntity;
import com.landgate.domain.group.model.entity.GroupEntity;
import com.landgate.trigger.gateway.limit.ConcurrencyService;
import com.landgate.trigger.gateway.limit.RateLimitSnapshot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 上游账号选择器 —— 负载感知的账户选择。
 * <p>
 * 选择流程：
 * <ol>
 *   <li>加载分组关联的全部候选账户（批量查询，避免 N+1）</li>
 *   <li>责任链过滤：删除 → 健康 → 模型白名单 → 显式路由</li>
 *   <li>排序：优先级(高→低) → 负载率(低→高) → 最后使用时间(远→近)</li>
 *   <li>返回排序后的第一个账户</li>
 * </ol>
 * <p>
 * 注意：Group provider/platform 是历史展示字段，不参与真实路由；Group 只是账号池。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AccountSelector {

    private final IAccountGroupRepository accountGroupRepository;
    private final IAccountRepository accountRepository;
    private final ConcurrencyService concurrencyService;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    // ---- 过滤器接口 ----

    /** 账户过滤器，每个过滤条件独立实现，可插拔可测试 */
    interface AccountFilter {
        /** @return true 表示通过，false 表示排除 */
        boolean pass(AccountEntity account, GroupEntity group, String model);

        /** 过滤器名称，用于日志排查 */
        String name();
    }

    /** 过滤器：跳过已删除的账户 */
    private static final AccountFilter DELETED_FILTER = new AccountFilter() {
        @Override
        public boolean pass(AccountEntity account, GroupEntity group, String model) {
            return account.getDeletedAt() == null;
        }
        @Override
        public String name() { return "DELETED"; }
    };

    /** 过滤器：跳过不健康 / 不可调度的账户 */
    private static final AccountFilter HEALTH_FILTER = new AccountFilter() {
        @Override
        public boolean pass(AccountEntity account, GroupEntity group, String model) {
            return account.isActive() && account.isSchedulable()
                    && !account.isRateLimited() && !account.isOverloaded();
        }
        @Override
        public String name() { return "HEALTH"; }
    };

    /** 过滤器：根据账户的 supportedModels 白名单过滤 */
    private final AccountFilter modelWhitelistFilter = new AccountFilter() {
        @Override
        public boolean pass(AccountEntity account, GroupEntity group, String model) {
            return model == null || isModelSupportedByAccount(account, model);
        }
        @Override
        public String name() { return "MODEL_WHITELIST"; }
    };

    /** 过滤器：显式路由（modelRouting），启用时优先按路由表匹配，无匹配时放行 */
    private final AccountFilter explicitRouteFilter = new AccountFilter() {
        @Override
        public boolean pass(AccountEntity account, GroupEntity group, String model) {
            if (model == null || !group.hasModelRoutingEnabled()) return true;
            Set<Long> routedIds = getRoutedAccountIds(group, model);
            if (routedIds == null) return true;  // 路由表无匹配 → 放行全部
            return routedIds.contains(account.getId());
        }
        @Override
        public String name() { return "EXPLICIT_ROUTE"; }
    };

    /** 过滤器链，按顺序执行：删除 → 健康 → 模型白名单 → 显式路由 */
    private final List<AccountFilter> filterChain = List.of(
            DELETED_FILTER,
            HEALTH_FILTER,
            modelWhitelistFilter,
            explicitRouteFilter
    );

    // ---- 公共方法 ----

    public AccountEntity getById(Long accountId) {
        if (accountId == null) return null;
        AccountEntity account = accountRepository.findById(accountId).orElse(null);
        if (account == null) {
            log.debug("账户不存在: account_id={}", accountId);
            return null;
        }
        if (account.getDeletedAt() != null) {
            log.debug("账户已删除: account_id={}", accountId);
            return null;
        }
        if (!account.isActive()) {
            log.debug("账户未激活: account_id={}", accountId);
            return null;
        }
        if (!account.isSchedulable()) {
            log.debug("账户不可调度: account_id={}, reason={}", accountId, account.getTempUnschedulableReason());
            return null;
        }
        if (account.isRateLimited()) {
            log.debug("账户正在限流冷却: account_id={}, reset_at={}", accountId, account.getRateLimitResetAt());
            return null;
        }
        if (account.isOverloaded()) {
            log.debug("账户过载冷却中: account_id={}, until={}", accountId, account.getOverloadUntil());
            return null;
        }
        return account;
    }

    /**
     * 选择最佳账户处理指定模型的请求。
     * <p>
     * Group provider/platform 不参与过滤；只在 group 绑定的账号池内按模型与健康状态筛选。
     *
     * @param group 分组实体
     * @param model 请求的模型名称
     * @return 选中的账户，无可用时返回 null
     */
    public AccountEntity selectAccount(GroupEntity group, String model) {
        return selectAccount(group, model, Collections.emptySet());
    }

    public AccountEntity selectAccount(GroupEntity group, String model, Set<Long> excludedIds) {
        if (group == null || group.getId() == null) {
            log.warn("Group is null or has no ID, cannot select account");
            return null;
        }

        // Step 0: 排除模型检查
        if (model != null && isModelExcluded(group, model)) {
            log.info("Model excluded by group config: model={}, group_id={}", model, group.getId());
            return null;
        }

        List<AccountGroupEntity> links = accountGroupRepository.findByGroupIdOrderByPriority(group.getId());
        if (links.isEmpty()) {
            log.warn("No accounts bound to group: group_id={}", group.getId());
            return null;
        }

        log.debug("Selecting account for group: group_id={}, candidates={}, model={}",
                group.getId(), links.size(), model);

        // 批量加载账户，避免 N+1 查询
        List<Long> accountIds = links.stream()
                .map(AccountGroupEntity::getAccountId)
                .toList();
        Map<Long, AccountEntity> accountMap = accountRepository.findByIds(accountIds).stream()
                .collect(Collectors.toMap(AccountEntity::getId, a -> a));

        // 遍历候选 → 跑过滤器链 → 计算负载率
        List<Candidate> candidates = new ArrayList<>();
        for (AccountGroupEntity link : links) {
            AccountEntity account = accountMap.get(link.getAccountId());
            if (account == null) continue;   // 账号已被删除或不存在
            if (excludedIds != null && excludedIds.contains(account.getId())) {
                log.info("账户被本次请求 failover 排除: account_id={}, name={}", account.getId(), account.getName());
                continue;
            }

            // 跑过滤器链
            boolean passed = true;
            for (AccountFilter filter : filterChain) {
                if (!filter.pass(account, group, model)) {
                    passed = false;
                    log.info("账户被 {} 过滤: account_id={}, name={}, platform={}",
                            filter.name(), account.getId(), account.getName(), account.getPlatform());
                    break;
                }
            }
            if (!passed) continue;

            double loadRate = calcLoadRate(account);
            log.debug("账户通过过滤: account_id={}, name={}, priority={}, load_rate={}, active={}, max={}",
                    account.getId(), account.getName(), link.getPriority(), String.format("%.2f", loadRate),
                    concurrencyService.getActiveCount(account.getId()), account.getConcurrency());
            candidates.add(new Candidate(account, link.getPriority(), loadRate));
        }

        if (candidates.isEmpty()) {
            log.warn("无可用账户: group={}, model={}, 总绑定数={}", group.getName(), model, links.size());
            return null;
        }

        // 排序：优先级(高→低) → 负载率(低→高) → 最后使用时间(远→近)
        candidates.sort(Comparator
                .comparingInt(Candidate::priority).reversed()
                .thenComparingDouble(Candidate::loadRate)
                .thenComparing(c -> c.account.getLastUsedAt() == null
                        ? Instant.EPOCH : c.account.getLastUsedAt()));

        AccountEntity selected = candidates.get(0).account;
        log.info("账户选择完成: account_id={}, name={}, platform={}, priority={}, load_rate={}, 候选数={}",
                selected.getId(), selected.getName(), selected.getPlatform(),
                candidates.get(0).priority, String.format("%.2f", candidates.get(0).loadRate), candidates.size());
        return selected;
    }

    // ---- 负载率 ----

    /**
     * 计算负载率 = 当前活跃并发数 / 有效并发上限。
     */
    private double calcLoadRate(AccountEntity account) {
        int active = concurrencyService.getActiveCount(account.getId());
        int max = account.getConcurrency();
        int loadFactor = account.getLoadFactor() != null ? account.getLoadFactor() : 100;
        int effectiveMax = max * loadFactor / 100;
        if (effectiveMax <= 0) return Double.MAX_VALUE;
        return (double) active / effectiveMax;
    }

    // ---- 模型排除与白名单 ----

    /** 检查请求的 model 是否在分组的排除模型列表中。 */
    private boolean isModelExcluded(GroupEntity group, String model) {
        String excludedJson = group.getExcludedModels();
        if (excludedJson == null || excludedJson.isEmpty()) return false;
        try {
            List<String> excluded = OBJECT_MAPPER.readValue(
                    excludedJson, new TypeReference<List<String>>() {});
            boolean result = excluded.contains(model);
            if (result) {
                log.info("模型被分组排除: model={}, group={}, excluded_list={}", model, group.getName(), excluded);
            }
            return result;
        } catch (Exception e) {
            log.debug("解析 excludedModels 失败: group_id={}", group.getId(), e);
            return false;
        }
    }

    /**
     * 检查号是否支持指定模型。
     * <p>
     * {@code null} / 空字符串 / {@code "[]"}（空数组）→ 不支持任何模型，返回 {@code false}。
     * {@code ["*"]} → 通配符，支持所有模型，返回 {@code true}。
     * 其他 → model 必须在白名单中。
     */
    public boolean isModelSupportedByAccount(AccountEntity account, String model) {
        String supportedJson = account.getSupportedModels();
        // null / 空字符串 / 空数组 [] = 不支持任何模型
        if (supportedJson == null || supportedJson.isEmpty() || "[]".equals(supportedJson)) {
            log.info("账户无模型白名单: account_id={}, name={}, supported_models=空", account.getId(), account.getName());
            return false;
        }
        try {
            List<String> supported = OBJECT_MAPPER.readValue(
                    supportedJson, new TypeReference<List<String>>() {});
            // ["*"] 通配符 = 支持所有模型
            if (supported.contains("*")) {
                return true;
            }
            boolean result = supported.contains(model);
            if (!result) {
                log.info("模型不在账户白名单: model={}, account_id={}, name={}, supported={}",
                        model, account.getId(), account.getName(), supported);
            }
            return result;
        } catch (Exception e) {
            log.debug("解析 supportedModels 失败: account_id={}", account.getId(), e);
            return false;
        }
    }

    // ---- 显式路由 ----

    /**
     * 解析 {@code modelRouting} JSON 获取指定模型的允许账户 ID 集合。
     *
     * @return 允许的账户 ID 集合，未启用路由或解析失败返回 null（表示放行）
     */
    private Set<Long> getRoutedAccountIds(GroupEntity group, String model) {
        if (!group.hasModelRoutingEnabled()) return null;
        String routingJson = group.getModelRouting();
        if (routingJson == null || routingJson.isEmpty() || "{}".equals(routingJson)) return null;

        try {
            Map<String, List<Object>> routing = OBJECT_MAPPER.readValue(
                    routingJson, new TypeReference<Map<String, List<Object>>>() {});
            // 先精确匹配 model，再通配符 "*"
            List<Object> ids = routing.get(model);
            if (ids == null) {
                ids = routing.get("*");
            }
            if (ids == null) return null;

            return ids.stream()
                    .map(o -> o instanceof Integer ? ((Integer) o).longValue() : (Long) o)
                    .collect(Collectors.toSet());
        } catch (Exception e) {
            log.warn("Failed to parse modelRouting for group: group_id={}", group.getId(), e);
            return null;
        }
    }

    // ---- 图片专用选择 ----

    /**
     * 图片生成专用账户选择 —— 固定走 OpenAI 平台。
     * <p>
     * 图片 API（/images/*）固定属于 OpenAI 平台。
     * 支持 capability 降级：优先 API Key 账户（Native），无可用时降级 OAuth 账户（Basic）。
     *
     * @param group       分组实体
     * @param model       请求的图片模型名称
     * @param capability  所需能力（"images-native" 或 "images-basic"）
     * @param excludedIds 已排除的账户 ID 集合（failover 用）
     * @return 选中的账户，无可用时返回 null
     */
    public AccountEntity selectAccountForImages(GroupEntity group, String model,
                                                 String capability, Set<Long> excludedIds) {
        if (group == null || group.getId() == null) {
            log.warn("Group is null or has no ID, cannot select image account");
            return null;
        }

        List<AccountGroupEntity> links = accountGroupRepository.findByGroupIdOrderByPriority(group.getId());
        if (links.isEmpty()) {
            log.warn("No accounts bound to group: group_id={}", group.getId());
            return null;
        }

        // 批量加载账户
        List<Long> accountIds = links.stream()
                .map(AccountGroupEntity::getAccountId)
                .toList();
        Map<Long, AccountEntity> accountMap = accountRepository.findByIds(accountIds).stream()
                .collect(Collectors.toMap(AccountEntity::getId, a -> a));

        List<Candidate> candidates = new ArrayList<>();
        for (AccountGroupEntity link : links) {
            AccountEntity account = accountMap.get(link.getAccountId());
            if (account == null) continue;

            // 图片 API 固定走 OpenAI 平台
            if (account.getPlatform() != com.landgate.types.enums.Platform.OPENAI) continue;
            if (excludedIds != null && excludedIds.contains(account.getId())) continue;
            if (!account.isActive()) continue;
            if (!account.isSchedulable()) continue;
            if (account.isRateLimited()) continue;
            if (account.isOverloaded()) continue;
            if (model != null && !isModelSupportedByAccount(account, model)) continue;

            // 根据 capability 过滤账户类型
            String accountType = account.getType() != null ? account.getType().name() : "";
            if ("images-native".equals(capability)) {
                if (!"API_KEY".equals(accountType) && !"UPSTREAM".equals(accountType)) continue;
            } else if ("images-basic".equals(capability)) {
                if (!"OAUTH".equals(accountType) && !"SETUP_TOKEN".equals(accountType)) continue;
            }

            double loadRate = calcLoadRate(account);
            candidates.add(new Candidate(account, link.getPriority(), loadRate));
        }

        if (candidates.isEmpty()) {
            log.debug("No available image accounts: group_id={}, capability={}", group.getId(), capability);
            return null;
        }

        candidates.sort(Comparator
                .comparingInt(Candidate::priority).reversed()
                .thenComparingDouble(Candidate::loadRate)
                .thenComparing(c -> c.account.getLastUsedAt() == null
                        ? Instant.EPOCH : c.account.getLastUsedAt()));

        AccountEntity selected = candidates.get(0).account;
        log.info("Image account selected: account_id={}, name={}, type={}, capability={}, load_rate={}",
                selected.getId(), selected.getName(), selected.getType(), capability,
                String.format("%.2f", candidates.get(0).loadRate));
        return selected;
    }

    // ---- 内部候选记录 ----

    private record Candidate(AccountEntity account, int priority, double loadRate) {}

    // ---- 健康标记方法 ----

    /**
     * 更新最后使用时间 + Rate Limit 窗口状态（合并写入，避免每次请求两次 DB 操作）。
     *
     * @param accountId 账号 ID
     * @param snapshot  上游 Rate Limit 快照（OAUTH 账号传入，其他类型传 null）
     */
    public void updateLastUsedAndRateLimits(Long accountId, RateLimitSnapshot snapshot) {
        accountRepository.findById(accountId).ifPresent(a -> {
            a.setLastUsedAt(Instant.now());
            if (snapshot != null && snapshot.hasData()) {
                a.setSessionWindowStart(snapshot.windowStart());
                a.setSessionWindowEnd(snapshot.windowEnd());
                a.setSessionWindowStatus(snapshot.statusJson());
            }
            accountRepository.save(a);
        });
    }

    public void updateLastUsed(Long accountId) {
        accountRepository.findById(accountId).ifPresent(a -> {
            a.setLastUsedAt(Instant.now());
            accountRepository.save(a);
        });
    }

    public void markRateLimited(Long accountId, Instant resetAt) {
        markRateLimited(accountId, resetAt, true);
    }

    /**
     * 标记账号进入限流冷却。
     * <p>
     * 上游明确返回 Retry-After 时尊重更长冷却；未返回 Retry-After 时，如果账号仍在冷却中，
     * 不刷新 reset_at，避免客户端连续重试导致冷却窗口无限顺延。
     */
    public void markRateLimited(Long accountId, Instant resetAt, boolean explicitRetryAfter) {
        accountRepository.findById(accountId).ifPresent(a -> {
            Instant currentResetAt = a.getRateLimitResetAt();
            Instant now = Instant.now();
            if (!explicitRetryAfter && currentResetAt != null && currentResetAt.isAfter(now)) {
                log.info("Account already rate-limited, keep existing reset_at: id={}, name={}, reset_at={}",
                        accountId, a.getName(), currentResetAt);
                return;
            }

            Instant effectiveResetAt = resetAt;
            if (explicitRetryAfter && currentResetAt != null && currentResetAt.isAfter(resetAt)) {
                effectiveResetAt = currentResetAt;
            }

            a.setRateLimitedAt(now);
            a.setRateLimitResetAt(effectiveResetAt);
            accountRepository.save(a);
            log.info("Account rate-limited: id={}, name={}, reset_at={}", accountId, a.getName(), effectiveResetAt);
        });
    }

    public void markOverloaded(Long accountId, Instant until) {
        accountRepository.findById(accountId).ifPresent(a -> {
            a.setOverloadUntil(until);
            accountRepository.save(a);
            log.info("Account overloaded: id={}, name={}, until={}", accountId, a.getName(), until);
        });
    }

    public void markTempUnschedulable(Long accountId, Instant until, String reason) {
        accountRepository.findById(accountId).ifPresent(a -> {
            a.setTempUnschedulableUntil(until);
            a.setTempUnschedulableReason(reason);
            accountRepository.save(a);
            log.info("Account temp-unschedulable: id={}, name={}, until={}, reason={}",
                    accountId, a.getName(), until, reason);
        });
    }

    /**
     * 将账号标记为 ERROR 状态 —— 用于凭证级永久故障（API Key 吊销、OAuth 无 refresh_token 等）。
     * 不会自动恢复，需要管理员手动介入。
     */
    public void markError(Long accountId, String reason) {
        accountRepository.findById(accountId).ifPresent(a -> {
            a.setStatus(com.landgate.types.enums.Status.ERROR);
            a.setErrorMessage(reason);
            accountRepository.save(a);
            log.warn("Account marked ERROR: id={}, name={}, reason={}", accountId, a.getName(), reason);
        });
    }
}
