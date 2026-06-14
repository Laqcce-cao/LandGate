package com.landgate.types.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Metadata user_id parser tests")
class MetadataUserIdParserTest {

    @Test
    @DisplayName("Parses Claude Code JSON metadata.user_id")
    void parsesJsonMetadataUserId() {
        var parsed = MetadataUserIdParser.parse("""
                {"device_id":"device-1","account_uuid":"account-1","session_id":"session-1"}
                """);

        assertNotNull(parsed);
        assertEquals("device-1", parsed.deviceId());
        assertEquals("account-1", parsed.accountUuid());
        assertEquals("session-1", parsed.sessionId());
        assertEquals(MetadataUserIdParser.Format.JSON, parsed.format());
    }

    @Test
    @DisplayName("Parses legacy metadata.user_id")
    void parsesLegacyMetadataUserId() {
        String device = "a".repeat(64);
        String account = "123e4567-e89b-12d3-a456-426614174000";
        String session = "123e4567-e89b-12d3-a456-426614174001";

        var parsed = MetadataUserIdParser.parse("user_" + device + "_account_" + account + "_session_" + session);

        assertNotNull(parsed);
        assertEquals(device, parsed.deviceId());
        assertEquals(account, parsed.accountUuid());
        assertEquals(session, parsed.sessionId());
        assertEquals(MetadataUserIdParser.Format.LEGACY, parsed.format());
    }

    @Test
    @DisplayName("Formats JSON for Claude Code 2.1.78 and newer, legacy before that")
    void formatVersionPolicy() {
        String json = MetadataUserIdParser.format("device", "account", "session", "2.1.78");
        assertTrue(json.startsWith("{"));

        String legacy = MetadataUserIdParser.format("a".repeat(64), "account", "session", "2.1.77");
        assertTrue(legacy.startsWith("user_"));
    }

    @Test
    @DisplayName("Rejects malformed metadata.user_id")
    void rejectsMalformedMetadataUserId() {
        assertNull(MetadataUserIdParser.parse(""));
        assertNull(MetadataUserIdParser.parse("{\"device_id\":\"d\"}"));
        assertNull(MetadataUserIdParser.parse("not-a-metadata-user-id"));
    }
}
