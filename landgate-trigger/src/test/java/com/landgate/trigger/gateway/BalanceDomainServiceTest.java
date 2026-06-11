package com.landgate.trigger.gateway;

import com.landgate.domain.auth.adapter.repository.IUserRepository;
import com.landgate.domain.auth.model.entity.UserEntity;
import com.landgate.infrastructure.balance.BalanceRedisService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 余额领域服务测试 —— 验证扣费失败不会被静默吞掉。
 */
@DisplayName("BalanceDomainService 测试")
class BalanceDomainServiceTest {

    @Test
    @DisplayName("Redis 重试仍扣费失败时抛出异常")
    void deductThrowsWhenRedisRetryFails() {
        BalanceRedisService balanceRedisService = mock(BalanceRedisService.class);
        IUserRepository userRepository = mock(IUserRepository.class);
        when(balanceRedisService.tryDeduct(3L, new BigDecimal("0.10"))).thenReturn(-2, -2);
        when(userRepository.findById(3L)).thenReturn(Optional.of(UserEntity.builder()
                .id(3L)
                .balance(new BigDecimal("10"))
                .build()));
        BalanceDomainService service = new BalanceDomainService(balanceRedisService, userRepository);

        assertThrows(IllegalStateException.class, () -> service.deduct(3L, new BigDecimal("0.10")));
    }

    @Test
    @DisplayName("Redis 缺失余额且用户不存在时抛出异常")
    void deductThrowsWhenUserMissing() {
        BalanceRedisService balanceRedisService = mock(BalanceRedisService.class);
        IUserRepository userRepository = mock(IUserRepository.class);
        when(balanceRedisService.tryDeduct(404L, new BigDecimal("0.10"))).thenReturn(-2);
        when(userRepository.findById(404L)).thenReturn(Optional.empty());
        BalanceDomainService service = new BalanceDomainService(balanceRedisService, userRepository);

        assertThrows(IllegalStateException.class, () -> service.deduct(404L, new BigDecimal("0.10")));
    }
}
