package com.landgate.domain.group.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.landgate.domain.account.adapter.repository.IAccountRepository;
import com.landgate.domain.account.model.entity.AccountEntity;
import com.landgate.domain.group.adapter.repository.IGroupRepository;
import com.landgate.domain.group.adapter.repository.IAccountGroupRepository;
import com.landgate.domain.group.adapter.repository.IUserAllowedGroupRepository;
import com.landgate.domain.group.model.entity.GroupEntity;
import com.landgate.domain.group.model.entity.AccountGroupEntity;
import com.landgate.domain.group.model.entity.UserAllowedGroupEntity;
import com.landgate.types.constant.RedisKeys;
import com.landgate.types.exception.NotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 分组管理领域服务 —— 分组的增删改查、账号绑定/解绑、用户授权/撤销。
 * <p>
 * 分组用于隔离不同的用户群体和上游资源：管理员将上游账号绑定到分组，
 * 再将分组授权给用户，用户通过分组访问对应的 AI 模型服务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GroupDomainService {

    private final IGroupRepository groupRepository;
    private final IAccountGroupRepository accountGroupRepository;
    private final IUserAllowedGroupRepository userAllowedGroupRepository;
    private final IAccountRepository accountRepository;
    private final RedissonClient redissonClient;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String WILDCARD_ALL = "[\"*\"]";
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);

    /**
     * 查询所有未删除的分组。
     *
     * @return 分组列表
     */
    public List<GroupEntity> listAll() { return groupRepository.findAll(); }

    /**
     * 根据 ID 查询分组。
     *
     * @param id 分组 ID
     * @return 分组实体
     * @throws NotFoundException 分组不存在时抛出
     */
    public GroupEntity getById(Long id) {
        return groupRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Group not found: " + id));
    }

    /**
     * 创建分组。
     *
     * @param group 分组实体
     * @return 保存后的分组（含自增 ID）
     */
    @Transactional
    public GroupEntity create(GroupEntity group) {
        group = groupRepository.save(group);
        log.info("Group created: id={}, name={}", group.getId(), group.getName());
        return group;
    }

    /**
     * 更新分组信息 —— 仅更新非空字段。
     *
     * @param id      分组 ID
     * @param updates 更新的字段
     * @return 更新后的分组实体
     */
    @Transactional
    public GroupEntity update(Long id, GroupEntity updates) {
        GroupEntity existing = getById(id);
        if (updates.getName() != null) existing.setName(updates.getName());
        if (updates.getDescription() != null) existing.setDescription(updates.getDescription());
        if (updates.getRateMultiplier() != null) existing.setRateMultiplier(updates.getRateMultiplier());
        if (updates.getIsExclusive() != null) existing.setIsExclusive(updates.getIsExclusive());
        if (updates.getStatus() != null) existing.setStatus(updates.getStatus());
        if (updates.getSubscriptionType() != null) existing.setSubscriptionType(updates.getSubscriptionType());
        if (updates.getDailyLimitUsd() != null) existing.setDailyLimitUsd(updates.getDailyLimitUsd());
        if (updates.getWeeklyLimitUsd() != null) existing.setWeeklyLimitUsd(updates.getWeeklyLimitUsd());
        if (updates.getMonthlyLimitUsd() != null) existing.setMonthlyLimitUsd(updates.getMonthlyLimitUsd());
        if (updates.getDefaultValidityDays() != null) existing.setDefaultValidityDays(updates.getDefaultValidityDays());
        if (updates.getAllowImageGeneration() != null) existing.setAllowImageGeneration(updates.getAllowImageGeneration());
        if (updates.getImageRateIndependent() != null) existing.setImageRateIndependent(updates.getImageRateIndependent());
        if (updates.getImageRateMultiplier() != null) existing.setImageRateMultiplier(updates.getImageRateMultiplier());
        if (updates.getImagePrice1k() != null) existing.setImagePrice1k(updates.getImagePrice1k());
        if (updates.getImagePrice2k() != null) existing.setImagePrice2k(updates.getImagePrice2k());
        if (updates.getImagePrice4k() != null) existing.setImagePrice4k(updates.getImagePrice4k());
        if (updates.getRpmLimit() != null) existing.setRpmLimit(updates.getRpmLimit());
        if (updates.getSortOrder() != null) existing.setSortOrder(updates.getSortOrder());
        if (updates.getExcludedModels() != null) existing.setExcludedModels(updates.getExcludedModels());
        groupRepository.save(existing);
        log.info("Group updated: id={}", id);
        return existing;
    }

    /**
     * 软删除分组。
     *
     * @param id 分组 ID
     */
    @Transactional
    public void delete(Long id) {
        groupRepository.delete(getById(id));
        log.info("Group deleted: id={}", id);
    }

    /**
     * 查询分组下绑定的所有上游账号。
     *
     * @param groupId 分组 ID
     * @return 账号-分组关联列表
     */
    public List<AccountGroupEntity> getAccounts(Long groupId) {
        return accountGroupRepository.findByGroupId(groupId);
    }

    /**
     * 将上游账号绑定到分组。
     *
     * @param groupId   分组 ID
     * @param accountId 账号 ID
     * @param priority  优先级（数值越小越优先）
     * @return 关联实体
     */
    @Transactional
    public AccountGroupEntity bindAccount(Long groupId, Long accountId, Integer priority) {
        getById(groupId);
        AccountGroupEntity link = AccountGroupEntity.builder()
                .groupId(groupId).accountId(accountId).priority(priority != null ? priority : 50).build();
        link = accountGroupRepository.save(link);
        invalidateSupportedModelsCache(groupId);
        log.info("Account bound: account_id={}, group_id={}", accountId, groupId);
        return link;
    }

    /**
     * 解绑分组下的上游账号。
     *
     * @param groupId   分组 ID
     * @param accountId 账号 ID
     */
    @Transactional
    public void unbindAccount(Long groupId, Long accountId) {
        accountGroupRepository.deleteByAccountId(accountId);
        invalidateSupportedModelsCache(groupId);
        log.info("Account unbound: account_id={}, group_id={}", accountId, groupId);
    }

    /**
     * 查询分组下授权的用户列表。
     *
     * @param groupId 分组 ID
     * @return 用户-分组授权列表
     */
    public List<UserAllowedGroupEntity> getUsers(Long groupId) {
        return userAllowedGroupRepository.findByGroupId(groupId);
    }

    /**
     * 授权用户访问分组。
     *
     * @param groupId 分组 ID
     * @param userId  用户 ID
     * @return 授权关联实体
     */
    @Transactional
    public UserAllowedGroupEntity authorizeUser(Long groupId, Long userId) {
        getById(groupId);
        UserAllowedGroupEntity link = UserAllowedGroupEntity.builder()
                .groupId(groupId).userId(userId).build();
        link = userAllowedGroupRepository.save(link);
        log.info("User authorized: user_id={}, group_id={}", userId, groupId);
        return link;
    }

    /**
     * 撤销用户对分组的访问权限。
     *
     * @param groupId 分组 ID
     * @param userId  用户 ID
     */
    @Transactional
    public void revokeUser(Long groupId, Long userId) {
        userAllowedGroupRepository.deleteByUserId(userId);
        log.info("User access revoked: user_id={}, group_id={}", userId, groupId);
    }

    /**
     * 检查指定模型是否被该分组排除。
     *
     * @param groupId 分组 ID
     * @param model   模型名称
     * @return true 表示该模型对该分组不可用
     */
    public boolean isModelExcluded(Long groupId, String model) {
        if (groupId == null || model == null) return false;
        GroupEntity group = getById(groupId);
        String excluded = group.getExcludedModels();
        if (excluded == null || excluded.isBlank()) return false;
        return excluded.contains("\"" + model + "\"");
    }

    // ==================== 分组支持模型（自动推导） ====================

    /**
     * 获取分组支持的模型列表 —— 所有关联 Account.supportedModels 的并集。
     * <p>
     * 推导规则：
     * <ul>
     *   <li>无关联 account → 返回 {@code []}</li>
     *   <li>任一 account 的 supportedModels 为空或 [] → 返回 {@code ["*"]}（不限制）</li>
     *   <li>所有 account 都有白名单 → 取并集</li>
     * </ul>
     * 结果缓存到 Redis，TTL 5 分钟。Account 绑定/解绑时自动失效。
     *
     * @param groupId 分组 ID
     * @return JSON 数组字符串
     */
    public String getSupportedModels(Long groupId) {
        if (groupId == null) return "[]";

        // 尝试从 Redis 缓存读取
        RBucket<String> bucket = redissonClient.getBucket(RedisKeys.groupSupportedModelsKey(groupId));
        String cached = bucket.get();
        if (cached != null) {
            return cached;
        }

        String result = computeSupportedModels(groupId);
        bucket.set(result, CACHE_TTL);
        return result;
    }

    /**
     * 实时计算分组支持的模型并集。
     */
    private String computeSupportedModels(Long groupId) {
        List<AccountGroupEntity> links = accountGroupRepository.findByGroupId(groupId);
        if (links.isEmpty()) {
            return "[]";
        }

        Set<String> allModels = new LinkedHashSet<>();
        for (AccountGroupEntity link : links) {
            AccountEntity account = accountRepository.findById(link.getAccountId())
                    .filter(a -> a.getDeletedAt() == null)
                    .orElse(null);
            if (account == null) continue;

            String supportedJson = account.getSupportedModels();
            // null / 空字符串 / [] 空数组 = 该号未配置或不支持任何模型 → 跳过
            if (supportedJson == null || supportedJson.isEmpty() || "[]".equals(supportedJson)) {
                continue;
            }

            try {
                List<String> models = OBJECT_MAPPER.readValue(
                        supportedJson, new TypeReference<List<String>>() {});
                // ["*"] 通配符 = 该号不限制 → 分组也不限制
                if (models.contains("*")) {
                    return WILDCARD_ALL;
                }
                allModels.addAll(models);
            } catch (Exception e) {
                log.debug("Failed to parse supportedModels for account: account_id={}", account.getId(), e);
            }
        }

        if (allModels.isEmpty()) return "[]";
        try {
            return OBJECT_MAPPER.writeValueAsString(allModels);
        } catch (Exception e) {
            return "[]";
        }
    }

    /**
     * 失效分组支持模型的 Redis 缓存。
     */
    public void invalidateSupportedModelsCache(Long groupId) {
        if (groupId == null) return;
        redissonClient.getBucket(RedisKeys.groupSupportedModelsKey(groupId)).delete();
        log.debug("Invalidated supported models cache: group_id={}", groupId);
    }
}
