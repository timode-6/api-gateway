package com.innowise.api_gateway.deadletter;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class RedisDeadLetterQueue {

    private static final String STREAM_KEY = "dlq:rollback-failed";

    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    public Mono<Void> publish(RollbackFailedEvent event) {
        return Mono.fromCallable(() -> objectMapper.writeValueAsString(event))
                .flatMap(json -> {
                    Map<String, String> fields = Map.of(
                            "payload",   json,
                            "timestamp", event.getTimestamp(),
                            "userId",    event.getUserId().toString()
                    );
                    return redisTemplate.opsForStream()
                            .add(STREAM_KEY, fields);
                })
                .doOnSuccess(id -> log.warn(
                        "[DLQ] Rollback failure published to stream. streamId={} userId={}",
                        id, event.getUserId()))
                .doOnError(ex -> log.error(
                        "[DLQ] CRITICAL — failed to publish rollback event for userId={}. " +
                        "Manual intervention required immediately. reason={}",
                        event.getUserId(), ex.getMessage()))
                .then();
    }
}