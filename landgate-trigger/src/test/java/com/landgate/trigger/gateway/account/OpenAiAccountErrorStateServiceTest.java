package com.landgate.trigger.gateway.account;

import com.landgate.domain.account.model.entity.AccountEntity;
import com.landgate.types.enums.Platform;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@DisplayName("OpenAI account error state service")
class OpenAiAccountErrorStateServiceTest {

    @Test
    @DisplayName("OpenAI 403 first hit marks temporary cooldown with counter state")
    void forbiddenFirstHitMarksTempUnschedulable() {
        AccountSelector accountSelector = mock(AccountSelector.class);
        FakeCounterStore counterStore = new FakeCounterStore(1);
        OpenAiAccountErrorStateService service =
                new OpenAiAccountErrorStateService(accountSelector, counterStore);

        service.markForbidden(openAiAccount(), "policy denied");

        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(accountSelector).markTempUnschedulable(eq(7L), any(Instant.class), reason.capture());
        assertEquals("OpenAI 403 temporary cooldown (1/3): Access forbidden (403): policy denied",
                reason.getValue());
        verify(accountSelector, never()).markError(eq(7L), any());
        assertEquals(1, counterStore.incrementCalls);
    }

    @Test
    @DisplayName("OpenAI 403 threshold marks account error")
    void forbiddenThresholdMarksError() {
        AccountSelector accountSelector = mock(AccountSelector.class);
        OpenAiAccountErrorStateService service =
                new OpenAiAccountErrorStateService(accountSelector, new FakeCounterStore(3));

        service.markForbidden(openAiAccount(), "policy denied");

        verify(accountSelector).markError(7L,
                "Access forbidden (403): policy denied | consecutive_403=3/3");
        verify(accountSelector, never()).markTempUnschedulable(eq(7L), any(), any());
    }

    @Test
    @DisplayName("OpenAI 403 counter unavailable marks account error like Sub2API fallback")
    void forbiddenWithoutCounterMarksError() {
        AccountSelector accountSelector = mock(AccountSelector.class);
        OpenAiAccountErrorStateService service =
                new OpenAiAccountErrorStateService(accountSelector, null);

        service.markForbidden(openAiAccount(), "policy denied");

        verify(accountSelector).markError(7L, "Access forbidden (403): policy denied");
        verify(accountSelector, never()).markTempUnschedulable(eq(7L), any(), any());
    }

    @Test
    @DisplayName("OpenAI 402 marks account error")
    void paymentRequiredMarksError() {
        AccountSelector accountSelector = mock(AccountSelector.class);
        OpenAiAccountErrorStateService service =
                new OpenAiAccountErrorStateService(accountSelector, new FakeCounterStore(1));

        service.markPaymentRequired(openAiAccount(), "billing failed");

        verify(accountSelector).markError(7L, "Payment required (402): billing failed");
    }

    @Test
    @DisplayName("OpenAI deactivated workspace 402 marks specific account error")
    void deactivatedWorkspaceMarksSpecificError() {
        AccountSelector accountSelector = mock(AccountSelector.class);
        OpenAiAccountErrorStateService service =
                new OpenAiAccountErrorStateService(accountSelector, new FakeCounterStore(1));

        service.markPaymentRequired(openAiAccount(),
                "{\"detail\":{\"code\":\"deactivated_workspace\"}}",
                "billing failed");

        verify(accountSelector).markError(7L,
                "Workspace deactivated (402): workspace has been deactivated");
    }

    @Test
    @DisplayName("OpenAI permanent 401 marks account error")
    void permanentUnauthorizedMarksError() {
        AccountSelector accountSelector = mock(AccountSelector.class);
        OpenAiAccountErrorStateService service =
                new OpenAiAccountErrorStateService(accountSelector, new FakeCounterStore(1));

        service.markPermanentUnauthorized(openAiAccount(),
                "{\"error\":{\"code\":\"token_revoked\",\"message\":\"revoked upstream\"}}",
                "revoked upstream");

        verify(accountSelector).markError(7L, "Token revoked (401): revoked upstream");
    }

    @Test
    @DisplayName("Normal OpenAI 401 is left to the handler refresh path")
    void normalUnauthorizedDoesNotMarkErrorInService() {
        AccountSelector accountSelector = mock(AccountSelector.class);
        OpenAiAccountErrorStateService service =
                new OpenAiAccountErrorStateService(accountSelector, new FakeCounterStore(1));

        service.markPermanentUnauthorized(openAiAccount(),
                "{\"error\":{\"message\":\"expired token\"}}",
                "expired token");

        verify(accountSelector, never()).markError(any(), any());
    }

    @Test
    @DisplayName("OpenAI OAuth 401 marks temporary cooldown")
    void oauthUnauthorizedMarksTempUnschedulable() {
        AccountSelector accountSelector = mock(AccountSelector.class);
        OpenAiAccountErrorStateService service =
                new OpenAiAccountErrorStateService(accountSelector, new FakeCounterStore(1));

        service.markOauthUnauthorized(openAiAccount(), "expired upstream");

        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(accountSelector).markTempUnschedulable(eq(7L), any(Instant.class), reason.capture());
        assertEquals("OAuth 401: expired upstream", reason.getValue());
        verify(accountSelector, never()).markError(any(), any());
    }

    @Test
    @DisplayName("Successful OpenAI response resets 403 counter")
    void successResetsCounter() {
        AccountSelector accountSelector = mock(AccountSelector.class);
        FakeCounterStore counterStore = new FakeCounterStore(1);
        OpenAiAccountErrorStateService service =
                new OpenAiAccountErrorStateService(accountSelector, counterStore);

        service.resetForbiddenCounterAfterSuccess(openAiAccount());

        assertTrue(counterStore.resetCalled);
        assertEquals(7L, counterStore.resetAccountId);
    }

    @Test
    @DisplayName("Non-OpenAI accounts are ignored")
    void nonOpenAiIgnored() {
        AccountSelector accountSelector = mock(AccountSelector.class);
        FakeCounterStore counterStore = new FakeCounterStore(1);
        OpenAiAccountErrorStateService service =
                new OpenAiAccountErrorStateService(accountSelector, counterStore);
        AccountEntity account = AccountEntity.builder()
                .id(9L)
                .platform(Platform.ANTHROPIC)
                .build();

        service.markForbidden(account, "forbidden");
        service.resetForbiddenCounterAfterSuccess(account);

        verify(accountSelector, never()).markError(any(), any());
        verify(accountSelector, never()).markTempUnschedulable(any(), any(), any());
        assertEquals(0, counterStore.incrementCalls);
        assertTrue(!counterStore.resetCalled);
    }

    private static AccountEntity openAiAccount() {
        return AccountEntity.builder()
                .id(7L)
                .platform(Platform.OPENAI)
                .build();
    }

    private static final class FakeCounterStore implements OpenAi403CounterStore {
        private final long count;
        private int incrementCalls;
        private boolean resetCalled;
        private Long resetAccountId;

        private FakeCounterStore(long count) {
            this.count = count;
        }

        @Override
        public long increment(Long accountId) {
            incrementCalls++;
            return count;
        }

        @Override
        public void reset(Long accountId) {
            resetCalled = true;
            resetAccountId = accountId;
        }
    }
}
