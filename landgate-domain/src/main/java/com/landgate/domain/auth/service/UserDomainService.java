package com.landgate.domain.auth.service;

import com.landgate.domain.auth.adapter.repository.IUserRepository;
import com.landgate.domain.auth.model.entity.UserEntity;
import com.landgate.types.exception.NotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 用户管理领域服务 —— 管理员对用户的查询、编辑、状态变更等操作。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserDomainService {

    private final IUserRepository userRepository;

    /**
     * 分页搜索用户列表。
     *
     * @param search   搜索关键词（匹配 username 或 email），可为空
     * @param page     页码（0-based）
     * @param pageSize 每页条数
     * @return 匹配的用户列表和总数
     */
    public List<UserEntity> listBySearch(String search, int page, int pageSize) {
        log.debug("List users: search={}, page={}, pageSize={}", search, page, pageSize);
        return userRepository.findBySearch(search, page, pageSize);
    }

    /**
     * 统计搜索匹配的用户总数。
     */
    public long countBySearch(String search) {
        return userRepository.countBySearch(search);
    }

    /**
     * 按 ID 查询用户。
     *
     * @throws NotFoundException 用户不存在时抛出
     */
    public UserEntity getById(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("User not found: " + id));
    }

    /**
     * 更新用户信息 —— 仅更新非 null 字段。
     */
    @Transactional
    public UserEntity update(Long id, UserEntity updates) {
        UserEntity existing = getById(id);
        if (updates.getUsername() != null) existing.setUsername(updates.getUsername());
        if (updates.getRole() != null) existing.setRole(updates.getRole());
        if (updates.getStatus() != null) existing.setStatus(updates.getStatus());
        if (updates.getBalance() != null) existing.setBalance(updates.getBalance());
        if (updates.getConcurrency() != null) existing.setConcurrency(updates.getConcurrency());
        if (updates.getRpmLimit() != null) existing.setRpmLimit(updates.getRpmLimit());
        if (updates.getNotes() != null) existing.setNotes(updates.getNotes());
        userRepository.save(existing);
        log.info("User updated: id={}", id);
        return existing;
    }

    /**
     * 更新用户状态（启用/禁用）。
     */
    @Transactional
    public void updateStatus(Long id, String status) {
        UserEntity user = getById(id);
        user.setStatus(status);
        userRepository.save(user);
        log.info("User status changed: id={}, status={}", id, status);
    }

    /**
     * 管理员充值 —— 增加用户余额并累计充值总额。
     *
     * @param id     用户 ID
     * @param amount 充值金额（正数，USD）
     * @return 充值后的用户实体
     * @throws IllegalArgumentException 金额 <= 0
     */
    @Transactional
    public UserEntity recharge(Long id, BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("充值金额必须大于 0");
        }
        UserEntity user = getById(id);
        BigDecimal currentBalance = user.getBalance() != null ? user.getBalance() : BigDecimal.ZERO;
        BigDecimal currentRecharged = user.getTotalRecharged() != null ? user.getTotalRecharged() : BigDecimal.ZERO;

        user.setBalance(currentBalance.add(amount).setScale(4, RoundingMode.HALF_UP));
        user.setTotalRecharged(currentRecharged.add(amount).setScale(4, RoundingMode.HALF_UP));
        userRepository.save(user);

        log.info("User recharged: id={}, amount={}, newBalance={}", id, amount, user.getBalance());
        return user;
    }
}
