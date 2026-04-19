package com.innowise.api_gateway.deadletter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ReactiveStreamOperations;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.Map;
@ExtendWith(MockitoExtension.class)
class RedisDeadLetterQueueTest {

    @Mock
    private ReactiveRedisTemplate<String, String> redisTemplate;

    @Mock
    private ReactiveStreamOperations<String, Object, Object> streamOps;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private RedisDeadLetterQueue deadLetterQueue;

    private static final String STREAM_KEY = "dlq:rollback-failed";

    private RollbackFailedEvent sampleEvent() {
        return RollbackFailedEvent.builder()
                .userId(42L)
                .reason("Auth service 500")
                .requiredAction("DELETE /api/users/42")
                .timestamp(Instant.now().toString())
                .rollbackAttempts(3)
                .build();
    }

    @BeforeEach
    void setUp() {
        Mockito.lenient().when(redisTemplate.opsForStream()).thenReturn(streamOps);
    }

    @Test
    void publish_HappyPath_ShouldWriteToStream() throws Exception {
        String expectedJson = "{\"userId\":42}";
        RecordId recordId = RecordId.of("1234567890-0");

        Mockito.when(objectMapper.writeValueAsString(Mockito.any()))
               .thenReturn(expectedJson);
        Mockito.when(streamOps.add(Mockito.eq(STREAM_KEY), Mockito.anyMap()))
               .thenReturn(Mono.just(recordId));

        StepVerifier.create(deadLetterQueue.publish(sampleEvent()))
                .verifyComplete();

        Mockito.verify(streamOps).add(
            Mockito.eq(STREAM_KEY),
            Mockito.<Map<String, String>>argThat(map -> 
                    map.containsKey("payload") &&
                    map.containsKey("timestamp") &&
                    map.containsKey("userId") &&
                    expectedJson.equals(map.get("payload"))
            )
        );
    }


    @Test
    void publish_SerializationFails_ShouldPropagateError() throws Exception {
        Mockito.when(objectMapper.writeValueAsString(Mockito.any()))
               .thenThrow(new JsonProcessingException("boom") {});

        StepVerifier.create(deadLetterQueue.publish(sampleEvent()))
                .expectErrorMatches(JsonProcessingException.class::isInstance)
                .verify();

        Mockito.verify(streamOps, Mockito.never()).add(Mockito.any(), Mockito.anyMap());
    }


    @Test
    void publish_RedisFails_ShouldPropagateError() throws Exception {
        Mockito.when(objectMapper.writeValueAsString(Mockito.any()))
               .thenReturn("{\"userId\":42}");
        Mockito.when(streamOps.add(Mockito.eq(STREAM_KEY), Mockito.anyMap()))
               .thenReturn(Mono.error(new RedisConnectionFailureException("Redis down")));

        StepVerifier.create(deadLetterQueue.publish(sampleEvent()))
                .expectErrorMatches(RedisConnectionFailureException.class::isInstance)
                .verify();
    }


    @Test
    void publish_ShouldAlwaysIncludeUserIdAndTimestamp() throws Exception {
        RollbackFailedEvent event = sampleEvent();
        RecordId recordId = RecordId.of("999-0");

        Mockito.when(objectMapper.writeValueAsString(Mockito.any()))
               .thenReturn("{}");
        Mockito.when(streamOps.add(Mockito.eq(STREAM_KEY), Mockito.anyMap()))
               .thenReturn(Mono.just(recordId));

        StepVerifier.create(deadLetterQueue.publish(event))
                .verifyComplete();

        Mockito.verify(streamOps).add(
                Mockito.eq(STREAM_KEY),
                Mockito.<Map<String, String>>argThat(map -> 
                        "42".equals(map.get("userId")) &&
                        map.get("timestamp") != null
                )
        );
    }
}