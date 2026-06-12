package com.landgate.trigger.gateway;

import com.landgate.domain.account.model.entity.AccountEntity;
import com.landgate.domain.group.model.entity.GroupEntity;
import com.landgate.trigger.gateway.converter.ProtocolFormatResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ProtocolFormatResolver 协议字段解析测试")
class ProtocolFormatResolverTest {

    @Test
    @DisplayName("Group supportedProtocols 只限制客户端入口协议")
    void groupAllowsClientFormatFromSupportedProtocols() {
        GroupEntity unrestricted = GroupEntity.builder().supportedProtocols(null).build();
        GroupEntity restricted = GroupEntity.builder()
                .supportedProtocols("[\"messages\",\"responses\"]")
                .build();

        assertTrue(ProtocolFormatResolver.groupAllowsClientFormat(unrestricted, "chat_completions"));
        assertTrue(ProtocolFormatResolver.groupAllowsClientFormat(restricted, "messages"));
        assertTrue(ProtocolFormatResolver.groupAllowsClientFormat(restricted, "openai-responses"));
        assertFalse(ProtocolFormatResolver.groupAllowsClientFormat(restricted, "chat_completions"));
    }

    @Test
    @DisplayName("Account supportedProtocols 第一项决定上游协议")
    void accountFirstProtocolDeterminesUpstreamFormat() {
        AccountEntity account = AccountEntity.builder()
                .supportedProtocols("[\"openai-chat\",\"responses\"]")
                .build();
        AccountEntity missing = AccountEntity.builder().supportedProtocols("[]").build();

        assertEquals("chat_completions",
                ProtocolFormatResolver.resolveAccountUpstreamFormat(account, "responses"));
        assertEquals("messages",
                ProtocolFormatResolver.resolveAccountUpstreamFormat(missing, "messages"));
    }

    @Test
    @DisplayName("不支持的账号协议会回退到策略允许的默认协议")
    void accountProtocolFallsBackWhenNotAllowedByStrategy() {
        AccountEntity account = AccountEntity.builder()
                .supportedProtocols("[\"responses\"]")
                .build();

        assertEquals("messages", ProtocolFormatResolver.resolveAccountUpstreamFormat(
                account, "messages", java.util.Set.of("messages")));
    }
}
