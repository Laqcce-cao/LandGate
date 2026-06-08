package com.landgate.trigger.gateway.group;

import com.landgate.domain.group.adapter.repository.IGroupRepository;
import com.landgate.domain.group.model.entity.GroupEntity;
import com.landgate.trigger.gateway.oauth.ClaudeCodeOnlyException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("GatewayGroupResolver 测试")
class GatewayGroupResolverTest {

    @Test
    @DisplayName("非 claude_code_only 分组直接返回原分组")
    void returnsOriginalGroupWhenNotClaudeCodeOnly() {
        GroupEntity group = group(1L, "default", false, null);
        GatewayGroupResolver resolver = resolver(Map.of(1L, group));

        GroupEntity resolved = resolver.resolveGatewayGroup(group, false);

        assertSame(group, resolved);
    }

    @Test
    @DisplayName("非 Claude Code 客户端沿 fallback_group_id 降级")
    void resolvesFallbackForNonClaudeCodeClient() {
        GroupEntity restricted = group(1L, "cc-only", true, 2L);
        GroupEntity fallback = group(2L, "fallback", false, null);
        GatewayGroupResolver resolver = resolver(Map.of(1L, restricted, 2L, fallback));

        GroupEntity resolved = resolver.resolveGatewayGroup(restricted, false);

        assertEquals(2L, resolved.getId());
    }

    @Test
    @DisplayName("fallback group 环形引用时抛出异常")
    void rejectsFallbackCycles() {
        GroupEntity first = group(1L, "first", true, 2L);
        GroupEntity second = group(2L, "second", true, 1L);
        GatewayGroupResolver resolver = resolver(Map.of(1L, first, 2L, second));

        assertThrows(ClaudeCodeOnlyException.class,
                () -> resolver.resolveGatewayGroup(first, false));
    }

    private static GatewayGroupResolver resolver(Map<Long, GroupEntity> groups) {
        return new GatewayGroupResolver(new InMemoryGroupRepository(groups));
    }

    private static GroupEntity group(Long id, String name, boolean claudeCodeOnly, Long fallbackGroupId) {
        return GroupEntity.builder()
                .id(id)
                .name(name)
                .claudeCodeOnly(claudeCodeOnly)
                .fallbackGroupId(fallbackGroupId)
                .build();
    }

    private record InMemoryGroupRepository(Map<Long, GroupEntity> groups) implements IGroupRepository {
        @Override
        public Optional<GroupEntity> findById(Long id) {
            return Optional.ofNullable(groups.get(id));
        }

        @Override
        public GroupEntity save(GroupEntity entity) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<GroupEntity> findByName(String name) {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.util.List<GroupEntity> findByStatus(String status) {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.util.List<GroupEntity> findBySubscriptionType(String subscriptionType) {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.util.List<GroupEntity> findByIsExclusiveTrue() {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.util.List<GroupEntity> findAll() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void delete(GroupEntity entity) {
            throw new UnsupportedOperationException();
        }
    }
}
