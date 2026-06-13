package com.landgate.domain.account.service;

import com.landgate.domain.account.adapter.repository.IAccountRepository;
import com.landgate.domain.account.model.entity.AccountEntity;
import com.landgate.types.enums.AccountType;
import com.landgate.types.enums.Platform;
import com.landgate.types.exception.NotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 上游账号领域服务 —— 管理 AI 平台账号的生命周期。
 * <p>
 * 负责账号的增删改查、状态变更、可调度开关控制。
 * 账号是网关转发的上游目标，每个账号对应一个 AI 平台（OpenAI、Anthropic 等）的 API 凭证。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountDomainService {

    private final IAccountRepository accountRepository;

    /**
     * 创建上游账号。
     *
     * @param account 账号实体
     * @return 保存后的账号（含自增 ID）
     */
    @Transactional
    public AccountEntity create(AccountEntity account) {
        validateSingleUpstreamProtocol(account);
        account = accountRepository.save(account);
        log.info("Account created: id={}, name={}", account.getId(), account.getName());
        return account;
    }

    /**
     * 按 ID 查询账号。
     *
     * @param id 账号 ID
     * @return 账号实体
     * @throws NotFoundException 账号不存在时抛出
     */
    public AccountEntity getById(Long id) {
        return accountRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Account not found: " + id));
    }

    /** 查询所有未删除的上游账号列表 */
    public List<AccountEntity> listAll() {
        return accountRepository.findAll();
    }

    /**
     * 按平台查询上游账号。
     *
     * @param platform 平台名称（如 openai、anthropic）
     * @return 该平台下的所有账号列表
     */
    public List<AccountEntity> listByPlatform(String platform) {
        return accountRepository.findByPlatform(platform);
    }

    /**
     * 更新账号信息 —— 仅更新非空字段。
     *
     * @param id      账号 ID
     * @param updates 更新的字段（null 字段不更新）
     * @return 更新后的账号实体
     */
    @Transactional
    public AccountEntity update(Long id, AccountEntity updates) {
        AccountEntity existing = getById(id);
        if (updates.getName() != null) existing.setName(updates.getName());
        if (updates.getNotes() != null) existing.setNotes(updates.getNotes());
        if (updates.getPlatform() != null) existing.setPlatform(updates.getPlatform());
        if (updates.getType() != null) existing.setType(updates.getType());
        // OAuth 账号凭证由系统管理，不允许通过通用编辑接口覆盖，防止加密 token 被破坏
        if (updates.getCredentials() != null && existing.getType() != AccountType.OAUTH) {
            existing.setCredentials(updates.getCredentials());
        }
        if (updates.getExtra() != null) existing.setExtra(updates.getExtra());
        if (updates.getProxyId() != null) existing.setProxyId(updates.getProxyId());
        if (updates.getConcurrency() != null) existing.setConcurrency(updates.getConcurrency());
        if (updates.getPriority() != null) existing.setPriority(updates.getPriority());
        if (updates.getLoadFactor() != null) existing.setLoadFactor(updates.getLoadFactor());
        if (updates.getSchedulable() != null) existing.setSchedulable(updates.getSchedulable());
        if (updates.getExpiresAt() != null) existing.setExpiresAt(updates.getExpiresAt());
        if (updates.getAutoPauseOnExpired() != null) existing.setAutoPauseOnExpired(updates.getAutoPauseOnExpired());
        if (updates.getSupportedModels() != null) existing.setSupportedModels(updates.getSupportedModels());
        if (updates.getSupportedProtocols() != null) existing.setSupportedProtocols(updates.getSupportedProtocols());
        if (updates.getMixedScheduling() != null) existing.setMixedScheduling(updates.getMixedScheduling());
        validateSingleUpstreamProtocol(existing);
        accountRepository.save(existing);
        log.info("Account updated: id={}", id);
        return existing;
    }

    /**
     * 软删除账号。
     *
     * @param id 账号 ID
     */
    @Transactional
    public void delete(Long id) {
        accountRepository.deleteById(id);
        log.info("Account deleted: id={}", id);
    }

    /**
     * 更新账号状态（如 ACTIVE、DISABLED、ERROR）。
     *
     * @param id           账号 ID
     * @param status       新状态
     * @param errorMessage 错误信息（可选，正常状态可为 null）
     */
    @Transactional
    public void updateStatus(Long id, String status, String errorMessage) {
        AccountEntity account = getById(id);
        account.setStatus(com.landgate.types.enums.Status.valueOf(status.toUpperCase()));
        account.setErrorMessage(errorMessage);
        accountRepository.save(account);
        log.info("Account status changed: id={}, status={}", id, status);
    }

    /**
     * 设置账号的可调度开关。
     *
     * @param id          账号 ID
     * @param schedulable 是否可调度
     */
    @Transactional
    public void setSchedulable(Long id, boolean schedulable) {
        AccountEntity account = getById(id);
        account.setSchedulable(schedulable);
        accountRepository.save(account);
        log.info("Account schedulable set: id={}, schedulable={}", id, schedulable);
    }

    private static void validateSingleUpstreamProtocol(AccountEntity account) {
        if (account == null || account.getPlatform() == null) return;
        Set<String> allowed = allowedProtocols(account.getPlatform(), account.getType());
        if (allowed.isEmpty()) return;
        List<String> protocols = parseProtocols(account.getSupportedProtocols()).stream()
                .map(AccountDomainService::normalizeProtocol)
                .filter(value -> !value.isBlank() && !"*".equals(value))
                .distinct()
                .toList();
        if (protocols.size() != 1) {
            throw new IllegalArgumentException(
                    "Account supportedProtocols must contain exactly one upstream protocol");
        }
        if (!allowed.contains(protocols.get(0))) {
            throw new IllegalArgumentException(
                    "Account upstream protocol '" + protocols.get(0)
                            + "' is not supported for platform=" + account.getPlatform()
                            + ", type=" + account.getType());
        }
    }

    private static Set<String> allowedProtocols(Platform platform, AccountType type) {
        if (platform == Platform.OPENAI && type == AccountType.API_KEY) {
            return Set.of("chat_completions", "responses");
        }
        if (platform == Platform.OPENAI && type == AccountType.OAUTH) {
            return Set.of("responses");
        }
        if (platform == Platform.ANTHROPIC) {
            return Set.of("messages");
        }
        if (platform == Platform.GEMINI) {
            return Set.of("gemini");
        }
        return Set.of();
    }

    private static List<String> parseProtocols(String raw) {
        if (raw == null || raw.isBlank() || "[]".equals(raw.trim())) return List.of();
        String trimmed = raw.trim();
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            String inner = trimmed.substring(1, trimmed.length() - 1).trim();
            if (inner.isBlank()) return List.of();
            return java.util.Arrays.stream(inner.split(","))
                    .map(value -> value.trim().replace("\"", ""))
                    .filter(value -> !value.isBlank())
                    .toList();
        }
        return List.of(trimmed);
    }

    private static String normalizeProtocol(String raw) {
        if (raw == null) return "";
        String value = raw.trim().toLowerCase(Locale.ROOT)
                .replace('-', '_')
                .replace(' ', '_');
        while (value.contains("__")) {
            value = value.replace("__", "_");
        }
        return switch (value) {
            case "anthropic", "anthropic_messages", "messages_api" -> "messages";
            case "openai", "openai_chat", "chat", "chat_completion", "chat_completions" -> "chat_completions";
            case "openai_responses", "response", "responses_api" -> "responses";
            case "google", "google_gemini", "gemini_generate_content" -> "gemini";
            default -> value;
        };
    }
}
