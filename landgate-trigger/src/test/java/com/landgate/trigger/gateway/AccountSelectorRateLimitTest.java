package com.landgate.trigger.gateway;

import com.landgate.domain.account.adapter.repository.IAccountRepository;
import com.landgate.domain.account.model.entity.AccountEntity;
import com.landgate.domain.group.adapter.repository.IAccountGroupRepository;
import com.landgate.domain.group.model.entity.AccountGroupEntity;
import com.landgate.domain.group.model.entity.GroupEntity;
import com.landgate.trigger.gateway.account.AccountSelector;
import com.landgate.types.enums.AccountType;
import com.landgate.types.enums.Platform;
import com.landgate.types.enums.Status;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * AccountSelector 限流冷却测试 —— 验证 429 冷却窗口不会被无 Retry-After 的重复请求无限顺延。
 */
@DisplayName("AccountSelector 限流冷却测试")
class AccountSelectorRateLimitTest {

    @Test
    @DisplayName("无 Retry-After 且账号仍在冷却中时不刷新 resetAt")
    void markRateLimitedWithoutRetryAfterDoesNotExtendActiveCooldown() {
        InMemoryAccountRepository accountRepository = new InMemoryAccountRepository(AccountEntity.builder()
                .id(16L)
                .name("openai-oauth")
                .status(Status.ACTIVE)
                .rateLimitResetAt(Instant.now().plusSeconds(30))
                .build());
        AccountSelector selector = new AccountSelector(null, accountRepository, null);
        Instant originalResetAt = accountRepository.account.getRateLimitResetAt();

        selector.markRateLimited(16L, Instant.now().plusSeconds(10), false);

        assertEquals(originalResetAt, accountRepository.account.getRateLimitResetAt());
    }

    @Test
    @DisplayName("sticky 查询限流账号时返回 null，避免继续打已冷却账号")
    void getByIdReturnsNullForRateLimitedAccount() {
        InMemoryAccountRepository accountRepository = new InMemoryAccountRepository(AccountEntity.builder()
                .id(16L)
                .name("openai-oauth")
                .status(Status.ACTIVE)
                .schedulable(true)
                .rateLimitResetAt(Instant.now().plusSeconds(30))
                .build());
        AccountSelector selector = new AccountSelector(new EmptyAccountGroupRepository(), accountRepository, null);

        AccountEntity account = selector.getById(16L);

        assertNull(account);
    }

    @Test
    @DisplayName("未配置 supportedModels 时使用 OpenAI model_mapping 判断模型支持")
    void modelSupportUsesOpenAiModelMappingWhenSupportedModelsMissing() {
        AccountEntity account = AccountEntity.builder()
                .id(21L)
                .name("openai-api-key")
                .platform(Platform.OPENAI)
                .type(AccountType.API_KEY)
                .status(Status.ACTIVE)
                .credentials("""
                        {"model_mapping":{"gpt-5.*":"gpt-5.4"}}
                        """)
                .build();
        AccountSelector selector = new AccountSelector(null, null, null);

        assertEquals(true, selector.isModelSupportedByAccount(account, "gpt-5.3"));
        assertEquals(false, selector.isModelSupportedByAccount(account, "o3-mini"));
    }

    private static class InMemoryAccountRepository implements IAccountRepository {
        private AccountEntity account;

        InMemoryAccountRepository(AccountEntity account) {
            this.account = account;
        }

        @Override
        public List<AccountEntity> findByPlatform(String platform) {
            return List.of(account);
        }

        @Override
        public Optional<AccountEntity> findById(Long id) {
            return account != null && account.getId().equals(id) ? Optional.of(account) : Optional.empty();
        }

        @Override
        public List<AccountEntity> findByIds(List<Long> ids) {
            return account != null && ids.contains(account.getId()) ? List.of(account) : List.of();
        }

        @Override
        public AccountEntity save(AccountEntity entity) {
            this.account = entity;
            return entity;
        }

        @Override
        public void deleteById(Long id) {
            if (account != null && account.getId().equals(id)) account = null;
        }

        @Override
        public List<AccountEntity> findAll() {
            return account == null ? List.of() : List.of(account);
        }

        @Override
        public long count() {
            return account == null ? 0 : 1;
        }

        @Override
        public long countByStatus(String status) {
            return account != null && account.getStatus().name().equals(status) ? 1 : 0;
        }
    }

    private static class EmptyAccountGroupRepository implements IAccountGroupRepository {
        @Override
        public List<AccountGroupEntity> findByGroupId(Long groupId) {
            return List.of();
        }

        @Override
        public List<AccountGroupEntity> findByGroupIdOrderByPriority(Long groupId) {
            return List.of();
        }

        @Override
        public List<AccountGroupEntity> findByAccountId(Long accountId) {
            return List.of();
        }

        @Override
        public AccountGroupEntity save(AccountGroupEntity entity) {
            return entity;
        }

        @Override
        public void deleteByGroupId(Long groupId) {
        }

        @Override
        public void deleteByAccountId(Long accountId) {
        }
    }
}
