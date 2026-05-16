package com.landgate.domain.group.service;

import com.landgate.domain.group.adapter.repository.IGroupRepository;
import com.landgate.domain.group.adapter.repository.IAccountGroupRepository;
import com.landgate.domain.group.adapter.repository.IUserAllowedGroupRepository;
import com.landgate.domain.group.model.entity.GroupEntity;
import com.landgate.domain.group.model.entity.AccountGroupEntity;
import com.landgate.domain.group.model.entity.UserAllowedGroupEntity;
import com.landgate.types.exception.NotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

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
        if (updates.getPlatform() != null) existing.setPlatform(updates.getPlatform());
        if (updates.getSubscriptionType() != null) existing.setSubscriptionType(updates.getSubscriptionType());
        if (updates.getDailyLimitUsd() != null) existing.setDailyLimitUsd(updates.getDailyLimitUsd());
        if (updates.getWeeklyLimitUsd() != null) existing.setWeeklyLimitUsd(updates.getWeeklyLimitUsd());
        if (updates.getMonthlyLimitUsd() != null) existing.setMonthlyLimitUsd(updates.getMonthlyLimitUsd());
        if (updates.getDefaultValidityDays() != null) existing.setDefaultValidityDays(updates.getDefaultValidityDays());
        if (updates.getAllowImageGeneration() != null) existing.setAllowImageGeneration(updates.getAllowImageGeneration());
        if (updates.getImageRateIndependent() != null) existing.setImageRateIndependent(updates.getImageRateIndependent());
        if (updates.getRpmLimit() != null) existing.setRpmLimit(updates.getRpmLimit());
        if (updates.getSortOrder() != null) existing.setSortOrder(updates.getSortOrder());
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
}
