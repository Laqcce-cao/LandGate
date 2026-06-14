package com.landgate.trigger.gateway.client;

import com.landgate.domain.account.model.entity.AccountEntity;
import com.landgate.trigger.gateway.route.EndpointKind;
import com.landgate.trigger.gateway.route.UpstreamRoute;
import com.landgate.types.enums.AccountType;
import com.landgate.types.enums.Platform;
import com.landgate.types.gateway.OpenAiCodexProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("OpenAiCodexClientRestrictionPolicy tests")
class OpenAiCodexClientRestrictionPolicyTest {

    @Test
    @DisplayName("Disabled unless selected account is OpenAI OAuth Codex with codex_cli_only=true")
    void restrictionEnablementRequiresOpenAiOauthCodexRouteAndAccountFlag() {
        UpstreamRoute codexRoute = route(EndpointKind.OPENAI_CODEX_RESPONSES);
        UpstreamRoute publicRoute = route(EndpointKind.OPENAI_RESPONSES);

        assertFalse(OpenAiCodexClientRestrictionPolicy.isRestrictionEnabled(
                account(Platform.OPENAI, AccountType.API_KEY, "{\"codex_cli_only\":true}"), codexRoute));
        assertFalse(OpenAiCodexClientRestrictionPolicy.isRestrictionEnabled(
                account(Platform.OPENAI, AccountType.OAUTH, "{\"codex_cli_only\":true}"), publicRoute));
        assertFalse(OpenAiCodexClientRestrictionPolicy.isRestrictionEnabled(
                account(Platform.OPENAI, AccountType.OAUTH, "{\"codex_cli_only\":false}"), codexRoute));
        assertFalse(OpenAiCodexClientRestrictionPolicy.isRestrictionEnabled(
                account(Platform.OPENAI, AccountType.OAUTH, "{\"codex_cli_only\":\"true\"}"), codexRoute));
        assertTrue(OpenAiCodexClientRestrictionPolicy.isRestrictionEnabled(
                account(Platform.OPENAI, AccountType.OAUTH, "{\"codex_cli_only\":true}"), codexRoute));
    }

    @Test
    @DisplayName("Rejects enabled OAuth Codex route when request is not from official Codex client")
    void rejectsNonOfficialClient() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(OpenAiCodexProfile.HEADER_USER_AGENT, "curl/8.0");

        var result = OpenAiCodexClientRestrictionPolicy.detect(
                account(Platform.OPENAI, AccountType.OAUTH, "{\"codex_cli_only\":true}"),
                route(EndpointKind.OPENAI_CODEX_RESPONSES),
                request);

        assertTrue(result.enabled());
        assertFalse(result.matched());
        assertTrue(result.rejected());
        assertEquals("not_official_codex_client", result.reason());
    }

    @Test
    @DisplayName("Allows enabled OAuth Codex route when User-Agent or Originator matches official Codex client")
    void allowsOfficialClientFamilies() {
        MockHttpServletRequest codexUa = new MockHttpServletRequest();
        codexUa.addHeader(OpenAiCodexProfile.HEADER_USER_AGENT, "codex_app/2.1.0");
        MockHttpServletRequest codexOriginator = new MockHttpServletRequest();
        codexOriginator.addHeader(OpenAiCodexProfile.HEADER_USER_AGENT, "curl/8.0");
        codexOriginator.addHeader(OpenAiCodexProfile.HEADER_ORIGINATOR, "codex_chatgpt_desktop");

        AccountEntity account = account(Platform.OPENAI, AccountType.OAUTH, "{\"codex_cli_only\":true}");
        UpstreamRoute route = route(EndpointKind.OPENAI_CODEX_RESPONSES);

        assertFalse(OpenAiCodexClientRestrictionPolicy.detect(account, route, codexUa).rejected());
        assertFalse(OpenAiCodexClientRestrictionPolicy.detect(account, route, codexOriginator).rejected());
    }

    private static AccountEntity account(Platform platform, AccountType type, String extra) {
        return AccountEntity.builder()
                .id(123L)
                .platform(platform)
                .type(type)
                .extra(extra)
                .build();
    }

    private static UpstreamRoute route(EndpointKind endpointKind) {
        return new UpstreamRoute(Platform.OPENAI, "responses", "responses", endpointKind,
                "https://example.test", false, true, "responses", "test");
    }
}
