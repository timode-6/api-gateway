package com.innowise.api_gateway.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TokenBlacklistServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private TokenBlacklistService service;

    @BeforeEach
    void setUp() {
        service = new TokenBlacklistService(redisTemplate);
    }


    @Test
    void isBlacklisted_WhenKeyExists_ShouldReturnTrue() {
        String token = "some.jwt.token";
        String key = "blacklist:" + token.hashCode();
        when(redisTemplate.hasKey(key)).thenReturn(true);

        Boolean result = service.isBlacklisted(token).block();

        assertThat(result).isTrue();
        verify(redisTemplate).hasKey(key);
    }

    @Test
    void isBlacklisted_WhenKeyDoesNotExist_ShouldReturnFalse() {
        String token = "clean.token";
        String key = "blacklist:" + token.hashCode();
        when(redisTemplate.hasKey(key)).thenReturn(false);

        Boolean result = service.isBlacklisted(token).block();

        assertThat(result).isFalse();
    }

    @Test
    void isBlacklisted_WhenRedisReturnsNull_ShouldReturnFalse() {
        String token = "token";
        String key = "blacklist:" + token.hashCode();
        when(redisTemplate.hasKey(key)).thenReturn(null);

        Boolean result = service.isBlacklisted(token).block();

        assertThat(result).isFalse();
    }


    @Test
    void blacklist_ShouldStoreKeyWithCorrectTtl() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        String token = "token.to.blacklist";
        String key = "blacklist:" + token.hashCode();

        service.blacklist(token, 3600L).block();

        verify(valueOperations).set(key, "1", Duration.ofSeconds(3600));
    }

    @Test
    void blacklist_ShouldUseHashCodeAsKeyDiscriminator() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        String tokenA = "tokenA";
        String tokenB = "tokenB";

        service.blacklist(tokenA, 60L).block();
        service.blacklist(tokenB, 60L).block();

        verify(valueOperations).set(
                eq("blacklist:" + tokenA.hashCode()), eq("1"), any(Duration.class));
        verify(valueOperations).set(
                eq("blacklist:" + tokenB.hashCode()), eq("1"), any(Duration.class));
    }

    @Test
    void blacklist_ShouldCompleteWithoutError() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        assertThat(service.blacklist("any-token", 100L).block()).isNull();
    }
}