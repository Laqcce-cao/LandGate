package com.landgate.trigger.gateway.oauth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.landgate.domain.account.adapter.repository.IAccountRepository;
import com.landgate.domain.account.model.entity.AccountEntity;
import com.landgate.domain.account.service.CredentialDomainService;
import com.landgate.infrastructure.config.OAuthProperties;
import com.landgate.types.constant.RedisKeys;
import com.landgate.types.enums.AccountType;
import com.landgate.types.enums.Platform;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RedissonClient;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("OAuth token refresh service")
class OAuthTokenRefreshServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @DisplayName("forceExpireAccessToken updates token_expires_at and schedules refresh")
    void forceExpireAccessTokenUpdatesCredentialsAndSchedule() throws Exception {
        IAccountRepository accountRepository = mock(IAccountRepository.class);
        RedissonClient redissonClient = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RScoredSortedSet<String> expirySet = mock(RScoredSortedSet.class);
        when(redissonClient.<String>getScoredSortedSet(RedisKeys.OAUTH_TOKEN_EXPIRY_KEY))
                .thenReturn(expirySet);
        AccountEntity account = AccountEntity.builder()
                .id(7L)
                .platform(Platform.OPENAI)
                .type(AccountType.OAUTH)
                .credentials("""
                        {"access_token":"old","refresh_token":"refresh","token_expires_at":"2099-01-01T00:00:00Z","token_encrypted":false}
                        """)
                .build();
        when(accountRepository.findById(7L)).thenReturn(Optional.of(account));
        when(accountRepository.save(any(AccountEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        OAuthTokenRefreshService service = new OAuthTokenRefreshService(
                new OAuthProperties(),
                mock(CredentialDomainService.class),
                accountRepository,
                redissonClient);
        Instant expiredAt = Instant.parse("2026-06-15T01:00:00Z");

        assertTrue(service.forceExpireAccessToken(7L, expiredAt));

        ArgumentCaptor<AccountEntity> saved = ArgumentCaptor.forClass(AccountEntity.class);
        verify(accountRepository).save(saved.capture());
        JsonNode credentials = JSON.readTree(saved.getValue().getCredentials());
        assertEquals(expiredAt.toString(), credentials.get("token_expires_at").asText());
        assertEquals("old", credentials.get("access_token").asText());
        assertEquals("refresh", credentials.get("refresh_token").asText());
        verify(expirySet).add(eq((double) expiredAt.getEpochSecond()), eq("7"));
    }
}
