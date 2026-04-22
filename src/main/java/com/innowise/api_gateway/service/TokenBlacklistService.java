package com.innowise.api_gateway.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;

@Slf4j
@Service
@RequiredArgsConstructor
public class TokenBlacklistService {

    private static final String BLACKLIST_PREFIX = "blacklist:";

    private final StringRedisTemplate redisTemplate;

    public Mono<Boolean> isBlacklisted(String token) {
        String key = BLACKLIST_PREFIX + token.hashCode();
        return Mono.fromCallable(() ->
                Boolean.TRUE.equals(redisTemplate.hasKey(key))
        ).subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<Void> blacklist(String token, long ttlSeconds) {
        String key = BLACKLIST_PREFIX + token.hashCode();
        return Mono.fromCallable(() -> {
            redisTemplate.opsForValue().set(key, "1", Duration.ofSeconds(ttlSeconds));
            log.info("Token blacklisted with TTL {}s", ttlSeconds);
            return null;
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }
}