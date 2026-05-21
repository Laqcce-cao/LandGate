package com.landgate.trigger.gateway;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.landgate.domain.account.adapter.repository.IAccountRepository;
import com.landgate.domain.account.model.entity.AccountEntity;
import com.landgate.domain.group.adapter.repository.IAccountGroupRepository;
import com.landgate.domain.group.model.entity.AccountGroupEntity;
import com.landgate.domain.group.model.entity.GroupEntity;
import com.landgate.types.enums.Platform;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

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

    private static final ObjectMapper MODEL_ROUTING_MAPPER = new ObjectMapper();

    /** Scope → Platform 映射：从模型范畴推导对应的上游平台 */
    private static final Map<String, Platform> SCOPE_PLATFORM = Map.of(
            "claude", Platform.ANTHROPIC,
            "gemini_text", Platform.GEMINI,
            "gemini_image", Platform.GEMINI,
            "openai", Platform.OPENAI,
            "antigravity", Platform.ANTIGRAVITY
    );

    public AccountEntity getById(Long accountId) {
        if (accountId == null) return null;
        return accountRepository.findById(accountId)
                .filter(a -> a.getDeletedAt() == null)
                .filter(AccountEntity::isActive)
                .filter(AccountEntity::isSchedulable)
                .orElse(null);
    }

    public AccountEntity selectAccount(GroupEntity group, String model) {
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
            if (model != null && !isModelSupportedByAccount(account, model)) {
                log.debug("Account does not support model: account_id={}, model={}", account.getId(), model);
                continue;
            }

            double loadRate = calcLoadRate(account);
            candidates.add(new Candidate(account, link.getPriority(), loadRate));
        }

        if (candidates.isEmpty()) {
            log.warn("No available account for group: group_id={}", group.getId());
            return null;
        }

        // Step 4: 模型感知过滤
        if (model != null) {
            List<Candidate> modelFiltered = applyModelFilter(candidates, group, model);
            if (modelFiltered.isEmpty()) {
                log.warn("No account supports model: model={}, group_id={}", model, group.getId());
                return null;
            }
            candidates = modelFiltered;
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

    // ---- 模型路由辅助方法 ----

    /**
     * 检查请求的 model 是否在分组的排除模型列表中。
     */
    private boolean isModelExcluded(GroupEntity group, String model) {
        String excludedJson = group.getExcludedModels();
        if (excludedJson == null || excludedJson.isEmpty()) return false;
        try {
            List<String> excluded = MODEL_ROUTING_MAPPER.readValue(
                    excludedJson, new TypeReference<List<String>>() {});
            return excluded.contains(model);
        } catch (Exception e) {
            log.debug("Failed to parse excludedModels for group: group_id={}", group.getId(), e);
            return false;
        }
    }

    /**
     * 应用模型感知过滤，返回支持目标 model 的候选账户列表。
     * <p>
     * 分两层过滤：
     * <ol>
     *   <li><b>显式路由</b>：如果 {@code modelRoutingEnabled=true}，
     *       解析 {@code modelRouting} JSON 查找模型对应的允许账户列表</li>
     *   <li><b>Scope→Platform 匹配</b>（兜底）：从 model 名推断 scope，
     *       对照 {@code supportedModelScopes}，过滤 platform 不匹配的账户</li>
     * </ol>
     */
    private List<Candidate> applyModelFilter(List<Candidate> candidates, GroupEntity group, String model) {
        // Layer A: 显式路由表
        Set<Long> routedAccountIds = getRoutedAccountIds(group, model);
        if (routedAccountIds != null) {
            List<Candidate> filtered = new ArrayList<>();
            for (Candidate c : candidates) {
                if (routedAccountIds.contains(c.account.getId())) {
                    filtered.add(c);
                }
            }
            if (!filtered.isEmpty()) {
                log.debug("Model routing matched: model={}, accounts={}, candidates={}",
                        model, routedAccountIds, filtered.size());
                return filtered;
            }
            // 显式路由表无匹配 → 约束过严，回退到 scope 过滤
            log.debug("Model routing table has no match for model={}, falling back to scope filter", model);
        }

        // Layer B: Scope → Platform 兜底
        String scope = determineModelScope(model);
        if (scope == null) {
            log.debug("Cannot determine scope for model={}, skipping platform filter", model);
            return candidates; // 无法判断 scope，不做过滤
        }

        if (!isScopeSupported(scope, group.getSupportedModelScopes())) {
            log.info("Model scope not supported: scope={}, model={}, group_id={}",
                    scope, model, group.getId());
            return List.of();
        }

        Platform expectedPlatform = scopeToPlatform(scope);
        if (expectedPlatform == null) {
            return candidates;
        }

        List<Candidate> filtered = new ArrayList<>();
        for (Candidate c : candidates) {
            if (c.account.getPlatform() == expectedPlatform) {
                filtered.add(c);
            }
        }

        log.debug("Scope filter: scope={}, platform={}, before={}, after={}",
                scope, expectedPlatform, candidates.size(), filtered.size());
        return filtered;
    }

    /**
     * 解析 {@code modelRouting} JSON 获取指定模型的允许账户 ID 集合。
     *
     * @return 允许的账户 ID 集合，未启用路由或解析失败返回 null
     */
    private Set<Long> getRoutedAccountIds(GroupEntity group, String model) {
        if (!group.hasModelRoutingEnabled()) return null;
        String routingJson = group.getModelRouting();
        if (routingJson == null || routingJson.isEmpty() || "{}".equals(routingJson)) return null;

        try {
            Map<String, List<Object>> routing = MODEL_ROUTING_MAPPER.readValue(
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

    /**
     * 从模型名称推断所属 scope。
     * <p>
     * 规则：含 "claude" → claude; 含 "gemini" + imagen/image → gemini_image;
     * 含 "gemini" → gemini_text; 含 "gpt/o1/o3/o4/dall-e" → openai。
     */
    private String determineModelScope(String model) {
        if (model == null) return null;
        String lower = model.toLowerCase();
        if (lower.contains("claude")) return "claude";
        if (lower.contains("gemini")) {
            if (lower.contains("imagen") || lower.contains("image")) return "gemini_image";
            return "gemini_text";
        }
        if (lower.contains("gpt") || lower.contains("o1-") || lower.contains("o3-")
                || lower.contains("o4-") || lower.startsWith("o1")
                || lower.startsWith("o3") || lower.startsWith("o4")
                || lower.contains("dall-e") || lower.contains("dalle")) return "openai";
        if (lower.contains("antigravity")) return "antigravity";
        return null;
    }

    /**
     * 检查 scope 是否在 supportedModelScopes JSON 数组中。
     */
    private boolean isScopeSupported(String scope, String supportedModelScopesJson) {
        if (supportedModelScopesJson == null || supportedModelScopesJson.isEmpty()) return true;
        try {
            List<String> scopes = MODEL_ROUTING_MAPPER.readValue(
                    supportedModelScopesJson, new TypeReference<List<String>>() {});
            return scopes.contains(scope);
        } catch (Exception e) {
            log.debug("Failed to parse supportedModelScopes: json={}", supportedModelScopesJson, e);
            return true; // 解析失败时放行
        }
    }

    /**
     * 将 scope 映射为 Platform 枚举。
     */
    private Platform scopeToPlatform(String scope) {
        return SCOPE_PLATFORM.get(scope);
    }

    /**
     * 检查号是否支持指定模型。
     * <p>
     * 若 {@code supportedModels} 为 {@code null} 或空 JSON 数组，表示不限制，
     * 返回 {@code true}。否则 model 必须在白名单中。
     */
    private boolean isModelSupportedByAccount(AccountEntity account, String model) {
        String supportedJson = account.getSupportedModels();
        if (supportedJson == null || supportedJson.isEmpty() || "[]".equals(supportedJson)) {
            return true;
        }
        try {
            List<String> supported = MODEL_ROUTING_MAPPER.readValue(
                    supportedJson, new TypeReference<List<String>>() {});
            return supported.contains(model);
        } catch (Exception e) {
            log.debug("Failed to parse supportedModels for account: account_id={}", account.getId(), e);
            return true; // 解析失败时放行
        }
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
