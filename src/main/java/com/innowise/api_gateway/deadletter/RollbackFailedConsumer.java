package com.innowise.api_gateway.deadletter;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
@Slf4j
@Service
@RequiredArgsConstructor
public class RollbackFailedConsumer {

    private static final String STREAM_KEY     = "dlq:rollback-failed";
    private static final String CONSUMER_GROUP = "ops-group";
    private static final String CONSUMER_NAME  = "gateway-1";

    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    @Value("${user.service.url}")
    private String userServiceUrl;

    @PostConstruct
    public void initGroup() {
        redisTemplate.opsForStream()
                .createGroup(STREAM_KEY, ReadOffset.from("0"), CONSUMER_GROUP)
                .onErrorComplete()
                .subscribe();
    }

    @Scheduled(fixedDelay = 30_000)
    public void processPendingRollbacks() {
        StreamOffset<String> offset = StreamOffset.create(STREAM_KEY, ReadOffset.lastConsumed());

        redisTemplate.opsForStream().read(                   
                        Consumer.from(CONSUMER_GROUP, CONSUMER_NAME),
                        StreamReadOptions.empty().count(10),
                        offset
                )
                .flatMap(this::attemptRollback)
                .subscribe(
                        null,
                        ex -> log.error("[DLQ] Consumer poll error: {}", ex.getMessage())
                );
    }

    private Mono<Void> attemptRollback(MapRecord<String, Object, Object> streamRecord) {
        Object rawPayload = streamRecord.getValue().get("payload");

        if (rawPayload == null) {
            log.warn("[DLQ] Record {} has no payload, acking to discard", streamRecord.getId());
            return ack(streamRecord.getId());
        }

        return Mono.fromCallable(() ->
                        objectMapper.readValue(rawPayload.toString(), RollbackFailedEvent.class))
                .flatMap(event -> {
                    log.info("[DLQ] Retrying rollback for userId={}", event.getUserId());
                    return webClient.delete()
                            .uri(userServiceUrl + "/api/users/" + event.getUserId())
                            .retrieve()
                            .bodyToMono(Void.class)
                            .then(Mono.defer(() -> ack(streamRecord.getId())))           
                            .doOnSuccess(v -> log.info(
                                    "[DLQ] Rollback succeeded, record acked for userId={}",
                                    event.getUserId()))
                            .doOnError(ex -> log.warn(
                                    "[DLQ] Retry still failing for userId={}, leaving unacked: {}",
                                    event.getUserId(), ex.getMessage()));
                })
                .onErrorResume(ex -> {
                    log.error("[DLQ] Failed to deserialize record {}: {}", streamRecord.getId(), ex.getMessage());
                    return Mono.empty();  
                });
    }

    private Mono<Void> ack(RecordId id) {
        return redisTemplate.opsForStream()
                .acknowledge(STREAM_KEY, CONSUMER_GROUP, id)
                .doOnSuccess(count -> log.debug("[DLQ] Acked record {}", id))
                .then();
    }
}