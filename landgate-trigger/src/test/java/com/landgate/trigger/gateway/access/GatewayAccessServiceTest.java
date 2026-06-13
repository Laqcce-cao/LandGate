package com.landgate.trigger.gateway.access;

import com.landgate.domain.auth.adapter.repository.IUserRepository;
import com.landgate.domain.auth.model.entity.UserEntity;
import com.landgate.domain.billing.service.BillingDomainService;
import com.landgate.domain.group.adapter.repository.IGroupRepository;
import com.landgate.domain.group.model.entity.GroupEntity;
import com.landgate.trigger.gateway.billing.BalanceDomainService;
import com.landgate.trigger.gateway.group.GatewayGroupResolver;
import com.landgate.types.enums.Status;
import com.landgate.types.exception.AuthenticationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("GatewayAccessService 测试")
class GatewayAccessServiceTest {

    private final FakeUserRepository userRepository = new FakeUserRepository();
    private final FakeBillingDomainService billingDomainService = new FakeBillingDomainService();
    private final FakeBalanceDomainService balanceDomainService = new FakeBalanceDomainService();
    private final FakeGroupRepository groupRepository = new FakeGroupRepository();
    private final GatewayGroupResolver gatewayGroupResolver = new GatewayGroupResolver(groupRepository);
    private final CapturingErrorWriter errorWriter = new CapturingErrorWriter();
    private final GatewayAccessService service = new GatewayAccessService(
            userRepository, billingDomainService, balanceDomainService, gatewayGroupResolver);

    @Test
    @DisplayName("缺少 API key 时返回 401")
    void missingApiKeyStops() throws Exception {
        GatewayAccessResult result = service.check("req-1", new MockHttpServletRequest(),
                new MockHttpServletResponse(), errorWriter);

        assertTrue(result.shouldStop());
        assertEquals(401, errorWriter.status);
        assertEquals("authentication_error", errorWriter.code);
        assertEquals("Missing API key", errorWriter.message);
    }

    @Test
    @DisplayName("Group 不存在时返回 403")
    void missingGroupStops() throws Exception {
        MockHttpServletRequest request = request();

        GatewayAccessResult result = service.check("req-1", request, new MockHttpServletResponse(), errorWriter);

        assertTrue(result.shouldStop());
        assertEquals(403, errorWriter.status);
        assertEquals("permission_error", errorWriter.code);
        assertEquals("API key has no group assigned. Contact admin to assign a group.", errorWriter.message);
    }

    @Test
    @DisplayName("Group 禁用时返回 403")
    void disabledGroupStops() throws Exception {
        MockHttpServletRequest request = request();
        groupRepository.group = GroupEntity.builder()
                .id(30L)
                .name("disabled")
                .status(Status.DISABLED)
                .build();

        GatewayAccessResult result = service.check("req-1", request, new MockHttpServletResponse(), errorWriter);

        assertTrue(result.shouldStop());
        assertEquals(403, errorWriter.status);
        assertEquals("permission_error", errorWriter.code);
        assertEquals("Group 'disabled' is disabled.", errorWriter.message);
    }

    @Test
    @DisplayName("API key quota 超限时返回 429")
    void quotaExceededStops() throws Exception {
        MockHttpServletRequest request = request();
        groupRepository.group = activeGroup();
        billingDomainService.quotaError = new AuthenticationException("Quota exceeded");

        GatewayAccessResult result = service.check("req-1", request, new MockHttpServletResponse(), errorWriter);

        assertTrue(result.shouldStop());
        assertEquals(429, errorWriter.status);
        assertEquals("quota_exceeded", errorWriter.code);
        assertEquals("Quota exceeded", errorWriter.message);
    }

    @Test
    @DisplayName("用户不存在时返回 401")
    void missingUserStops() throws Exception {
        MockHttpServletRequest request = request();
        groupRepository.group = activeGroup();

        GatewayAccessResult result = service.check("req-1", request, new MockHttpServletResponse(), errorWriter);

        assertTrue(result.shouldStop());
        assertEquals(401, errorWriter.status);
        assertEquals("authentication_error", errorWriter.code);
        assertEquals("User not found", errorWriter.message);
    }

    @Test
    @DisplayName("普通用户余额不足时返回 402")
    void insufficientBalanceStops() throws Exception {
        MockHttpServletRequest request = request();
        groupRepository.group = activeGroup();
        userRepository.user = UserEntity.builder().id(20L).build();
        balanceDomainService.hasBalance = false;

        GatewayAccessResult result = service.check("req-1", request, new MockHttpServletResponse(), errorWriter);

        assertTrue(result.shouldStop());
        assertEquals(402, errorWriter.status);
        assertEquals("insufficient_balance", errorWriter.code);
        assertEquals("Insufficient balance. Please recharge your account.", errorWriter.message);
    }

    @Test
    @DisplayName("校验通过时返回访问上下文")
    void allowedReturnsAccessContext() throws Exception {
        MockHttpServletRequest request = request();
        GroupEntity group = activeGroup();
        UserEntity user = UserEntity.builder().id(20L).build();
        groupRepository.group = group;
        userRepository.user = user;
        balanceDomainService.hasBalance = true;

        GatewayAccessResult result = service.check("req-1", request, new MockHttpServletResponse(), errorWriter);

        assertFalse(result.shouldStop());
        assertEquals(10L, result.apiKeyId());
        assertEquals(20L, result.userId());
        assertEquals(30L, result.groupId());
        assertSame(group, result.group());
        assertSame(user, result.user());
    }

    @Test
    @DisplayName("特权用户不检查余额")
    void privilegedUserSkipsBalanceCheck() throws Exception {
        MockHttpServletRequest request = request();
        UserEntity user = UserEntity.builder().id(20L).role("admin").build();
        groupRepository.group = activeGroup();
        userRepository.user = user;

        GatewayAccessResult result = service.check("req-1", request, new MockHttpServletResponse(), errorWriter);

        assertFalse(result.shouldStop());
        assertEquals(0, balanceDomainService.hasBalanceCalls);
    }

    private MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("api_key_id", 10L);
        request.setAttribute("user_id", 20L);
        request.setAttribute("group_id", 30L);
        return request;
    }

    private GroupEntity activeGroup() {
        return GroupEntity.builder().id(30L).name("default").build();
    }

    private static class FakeUserRepository implements IUserRepository {
        private UserEntity user;

        @Override public Optional<UserEntity> findById(Long id) { return Optional.ofNullable(user); }
        @Override public Optional<UserEntity> findByEmail(String email) { throw unsupported(); }
        @Override public UserEntity save(UserEntity entity) { throw unsupported(); }
        @Override public boolean existsByEmail(String email) { throw unsupported(); }
        @Override public long countByStatus(String status) { throw unsupported(); }
        @Override public long count() { throw unsupported(); }
        @Override public java.util.List<UserEntity> findBySearch(String search, int page, int pageSize) { throw unsupported(); }
        @Override public long countBySearch(String search) { throw unsupported(); }
        @Override public int updateBalance(Long id, java.math.BigDecimal newBalance) { throw unsupported(); }
        @Override public long countByCreatedAtAfter(java.time.Instant after) { throw unsupported(); }
    }

    private static class FakeGroupRepository implements IGroupRepository {
        private GroupEntity group;

        @Override public Optional<GroupEntity> findById(Long id) { return Optional.ofNullable(group); }
        @Override public Optional<GroupEntity> findByName(String name) { throw unsupported(); }
        @Override public java.util.List<GroupEntity> findByStatus(String status) { throw unsupported(); }
        @Override public java.util.List<GroupEntity> findBySubscriptionType(String subscriptionType) { throw unsupported(); }
        @Override public java.util.List<GroupEntity> findByIsExclusiveTrue() { throw unsupported(); }
        @Override public java.util.List<GroupEntity> findAll() { throw unsupported(); }
        @Override public GroupEntity save(GroupEntity entity) { throw unsupported(); }
        @Override public void delete(GroupEntity entity) { throw unsupported(); }
    }

    private static class FakeBillingDomainService extends BillingDomainService {
        private AuthenticationException quotaError;

        FakeBillingDomainService() {
            super(null, null, null);
        }

        @Override
        public void checkQuota(Long apiKeyId) {
            if (quotaError != null) {
                throw quotaError;
            }
        }
    }

    private static class FakeBalanceDomainService extends BalanceDomainService {
        private boolean hasBalance;
        private int hasBalanceCalls;

        FakeBalanceDomainService() {
            super(null, null);
        }

        @Override
        public boolean hasBalance(Long userId) {
            hasBalanceCalls++;
            return hasBalance;
        }
    }

    private static UnsupportedOperationException unsupported() {
        return new UnsupportedOperationException("not used by this test");
    }

    private static class CapturingErrorWriter implements com.landgate.trigger.gateway.error.IErrorWriter {
        private int status;
        private String code;
        private String message;

        @Override
        public void writeError(HttpServletResponse response, int status, String code, String message)
                throws IOException {
            this.status = status;
            this.code = code;
            this.message = message;
            response.setStatus(status);
        }
    }
}
