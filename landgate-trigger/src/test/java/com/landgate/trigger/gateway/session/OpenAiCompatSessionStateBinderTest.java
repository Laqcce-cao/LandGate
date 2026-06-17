package com.landgate.trigger.gateway.session;

import com.landgate.domain.account.model.entity.AccountEntity;
import com.landgate.domain.billing.model.valobj.UsageTokens;
import com.landgate.trigger.gateway.GatewayRequestContext;
import com.landgate.trigger.gateway.response.GatewayResponseResult;
import com.landgate.trigger.gateway.route.EndpointKind;
import com.landgate.trigger.gateway.route.UpstreamRoute;
import com.landgate.types.enums.AccountType;
import com.landgate.types.enums.Platform;
import com.landgate.types.gateway.OpenAiCodexProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RMapCache;

import javax.net.ssl.SSLSession;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("OpenAI compat session state binder tests")
class OpenAiCompatSessionStateBinderTest {

    @Test
    @DisplayName("API key compat success binds response id")
    void apiKeySuccessBindsResponseId() {
        OpenAiCompatSessionService service = newService();
        AccountEntity account = account(AccountType.API_KEY);
        GatewayRequestContext ctx = context();

        OpenAiCompatSessionStateBinder.bindSuccess(
                service,
                ctx,
                account,
                42L,
                responseHeaders(Map.of()),
                new GatewayResponseResult(UsageTokens.builder().build(), true, "resp_1"));

        assertEquals("resp_1", service.getResponseId(account, 42L, "stable-cache-key"));
    }

    @Test
    @DisplayName("OAuth compat success binds turn-state")
    void oauthSuccessBindsTurnState() {
        OpenAiCompatSessionService service = newService();
        AccountEntity account = account(AccountType.OAUTH);
        GatewayRequestContext ctx = context();

        OpenAiCompatSessionStateBinder.bindSuccess(
                service,
                ctx,
                account,
                42L,
                responseHeaders(Map.of(OpenAiCodexProfile.HEADER_X_CODEX_TURN_STATE, List.of("turn_state_1"))),
                new GatewayResponseResult(UsageTokens.builder().build(), true, "resp_ignored"));

        assertEquals("turn_state_1", service.getTurnState(account, 42L, "stable-cache-key"));
        assertEquals("", service.getResponseId(account, 42L, "stable-cache-key"));
    }

    @Test
    @DisplayName("unsupported previous_response_id disables continuation")
    void unsupportedPreviousResponseIdDisablesContinuation() {
        OpenAiCompatSessionService service = newService();
        AccountEntity account = account(AccountType.API_KEY);
        GatewayRequestContext ctx = context();
        ctx.setOpenAiCompatPreviousResponseId("resp_old");
        service.bindResponseId(account, 42L, "stable-cache-key", "resp_old");

        OpenAiCompatSessionStateBinder.PreviousResponseFailureAction action =
                OpenAiCompatSessionStateBinder.handlePreviousResponseFailure(
                        service,
                        ctx,
                        account,
                        42L,
                        400,
                        "{\"error\":{\"message\":\"Unsupported parameter: previous_response_id\"}}");

        assertTrue(action.retryWithoutContinuation());
        assertEquals(OpenAiCompatSessionStateBinder.PreviousResponseFailureKind.UNSUPPORTED, action.kind());
        assertEquals("resp_old", action.previousResponseId());
        assertTrue(service.isContinuationDisabled(account, 42L, "stable-cache-key"));
        assertEquals("", service.getResponseId(account, 42L, "stable-cache-key"));
    }

    @Test
    @DisplayName("not found previous_response_id deletes cached response id")
    void notFoundPreviousResponseIdDeletesCachedResponseId() {
        OpenAiCompatSessionService service = newService();
        AccountEntity account = account(AccountType.API_KEY);
        GatewayRequestContext ctx = context();
        ctx.setOpenAiCompatPreviousResponseId("resp_missing");
        service.bindResponseId(account, 42L, "stable-cache-key", "resp_missing");

        OpenAiCompatSessionStateBinder.PreviousResponseFailureAction action =
                OpenAiCompatSessionStateBinder.handlePreviousResponseFailure(
                        service,
                        ctx,
                        account,
                        42L,
                        404,
                        "{\"error\":{\"code\":\"previous_response_not_found\",\"message\":\"previous response not found\"}}");

        assertTrue(action.retryWithoutContinuation());
        assertEquals(OpenAiCompatSessionStateBinder.PreviousResponseFailureKind.NOT_FOUND, action.kind());
        assertEquals("resp_missing", action.previousResponseId());
        assertFalse(service.isContinuationDisabled(account, 42L, "stable-cache-key"));
        assertEquals("", service.getResponseId(account, 42L, "stable-cache-key"));
    }

    private static GatewayRequestContext context() {
        GatewayRequestContext ctx = GatewayRequestContext.builder()
                .requestId("req")
                .upstreamRoute(new UpstreamRoute(
                        Platform.OPENAI,
                        "messages",
                        "responses",
                        EndpointKind.OPENAI_RESPONSES,
                        "https://api.openai.com/v1/responses",
                        false,
                        false,
                        "responses",
                        "test"))
                .build();
        ctx.setOpenAiCompatPromptCacheKey("stable-cache-key");
        return ctx;
    }

    private static AccountEntity account(AccountType type) {
        return AccountEntity.builder()
                .id(6L)
                .platform(Platform.OPENAI)
                .type(type)
                .build();
    }

    private static HttpResponse<String> responseHeaders(Map<String, List<String>> headers) {
        HttpHeaders httpHeaders = HttpHeaders.of(headers, (name, value) -> true);
        return new HttpResponse<>() {
            @Override
            public int statusCode() {
                return 200;
            }

            @Override
            public HttpRequest request() {
                return null;
            }

            @Override
            public Optional<HttpResponse<String>> previousResponse() {
                return Optional.empty();
            }

            @Override
            public HttpHeaders headers() {
                return httpHeaders;
            }

            @Override
            public String body() {
                return "";
            }

            @Override
            public Optional<SSLSession> sslSession() {
                return Optional.empty();
            }

            @Override
            public URI uri() {
                return URI.create("https://example.test");
            }

            @Override
            public HttpClient.Version version() {
                return HttpClient.Version.HTTP_1_1;
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static OpenAiCompatSessionService newService() {
        RMapCache<String, String> responseIds = mapCache(new HashMap<>());
        RMapCache<String, String> turnStates = mapCache(new HashMap<>());
        RMapCache<String, Boolean> disabled = mapCache(new HashMap<>());
        RMapCache<String, String> digests = mapCache(new HashMap<>());
        return new OpenAiCompatSessionService(responseIds, turnStates, disabled, digests);
    }

    private static <V> RMapCache<String, V> mapCache(Map<String, V> backing) {
        return (RMapCache<String, V>) Proxy.newProxyInstance(
                OpenAiCompatSessionStateBinderTest.class.getClassLoader(),
                new Class<?>[]{RMapCache.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "get" -> backing.get((String) args[0]);
                    case "put" -> {
                        backing.put((String) args[0], (V) args[1]);
                        yield null;
                    }
                    case "remove" -> backing.remove((String) args[0]);
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        return null;
    }
}
