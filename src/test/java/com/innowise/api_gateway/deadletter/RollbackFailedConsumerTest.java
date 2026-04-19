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
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ReactiveStreamOperations;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;

@ExtendWith(MockitoExtension.class)
class RollbackFailedConsumerTest {

    @Mock
    private ReactiveRedisTemplate<String, String> redisTemplate;

    @Mock
    private ReactiveStreamOperations<String, Object, Object> streamOps;

    @Mock
    private WebClient webClient;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private RollbackFailedConsumer consumer;

    @Mock private WebClient.RequestHeadersUriSpec<?> requestHeadersUriSpec;
@Mock private WebClient.RequestHeadersSpec    requestHeadersSpec;
@Mock private WebClient.ResponseSpec             responseSpec;

    private static final String STREAM_KEY     = "dlq:rollback-failed";
    private static final String CONSUMER_GROUP = "ops-group";

    @BeforeEach
    void setUp() {
        Mockito.when(redisTemplate.opsForStream()).thenReturn(streamOps);
        ReflectionTestUtils.setField(consumer, "userServiceUrl", "http://localhost:8082");
    }

    @Test
    void initGroup_ShouldCreateConsumerGroup() {
        Mockito.when(streamOps.createGroup(
                Mockito.eq(STREAM_KEY),
                Mockito.any(ReadOffset.class),
                Mockito.eq(CONSUMER_GROUP)))
               .thenReturn(Mono.just("OK"));

        consumer.initGroup();

        Mockito.verify(streamOps).createGroup(
                Mockito.eq(STREAM_KEY),
                Mockito.any(ReadOffset.class),
                Mockito.eq(CONSUMER_GROUP)
        );
    }

    @Test
    void initGroup_GroupAlreadyExists_ShouldCompleteWithoutError() {
        Mockito.when(streamOps.createGroup(
                Mockito.any(), Mockito.any(), Mockito.any()))
               .thenReturn(Mono.error(new RedisSystemException(
                       "BUSYGROUP Consumer Group already exists", null)));

        assertThatCode(() -> consumer.initGroup()).doesNotThrowAnyException();
    }

    @Test
    void processPendingRollbacks_EmptyStream_ShouldDoNothing() {
        Mockito.when(streamOps.read(
                Mockito.any(Consumer.class),
                Mockito.any(StreamReadOptions.class),
                Mockito.<StreamOffset<String>>any()))
               .thenReturn(Flux.empty());

        assertThatCode(() -> consumer.processPendingRollbacks()).doesNotThrowAnyException();

        Mockito.verify(webClient, Mockito.never()).delete();
    }

    @Test
    @SuppressWarnings("unchecked")
    void processPendingRollbacks_NullPayload_ShouldAckAndDiscard() {
        MapRecord<String, Object, Object> streamRecord = buildRecord(RecordId.of("1-0"), null);

        Mockito.when(streamOps.read(
                Mockito.any(Consumer.class),
                Mockito.any(StreamReadOptions.class),
                Mockito.<StreamOffset<String>>any()))
               .thenReturn(Flux.just(streamRecord));

        Mockito.when(streamOps.acknowledge(STREAM_KEY, CONSUMER_GROUP, RecordId.of("1-0")))
               .thenReturn(Mono.just(1L));

        consumer.processPendingRollbacks();

        Mockito.verify(streamOps).acknowledge(STREAM_KEY, CONSUMER_GROUP, RecordId.of("1-0"));
        Mockito.verify(webClient, Mockito.never()).delete();
    }

    @Test
    @SuppressWarnings("unchecked")
    void processPendingRollbacks_ValidRecord_RollbackSucceeds_ShouldAck() throws Exception {
        RecordId recordId = RecordId.of("2-0");
        String payload = "{\"userId\":10,\"reason\":\"test\",\"requiredAction\":\"DELETE /api/users/10\"}";
        MapRecord<String, Object, Object> streamRecord = buildRecord(recordId, payload);

        RollbackFailedEvent event = RollbackFailedEvent.builder()
                .userId(10L).reason("test")
                .requiredAction("DELETE /api/users/10")
                .timestamp(Instant.now().toString())
                .rollbackAttempts(3)
                .build();

        Mockito.when(streamOps.read(
                Mockito.any(Consumer.class),
                Mockito.any(StreamReadOptions.class),
                Mockito.<StreamOffset<String>>any()))
               .thenReturn(Flux.just(streamRecord));

        Mockito.when(objectMapper.readValue(payload, RollbackFailedEvent.class))
               .thenReturn(event);

        stubDeleteChain(Mono.empty());

        Mockito.when(streamOps.acknowledge(STREAM_KEY, CONSUMER_GROUP, recordId))
               .thenReturn(Mono.just(1L));

        consumer.processPendingRollbacks();

        Mockito.verify(streamOps).acknowledge(STREAM_KEY, CONSUMER_GROUP, recordId);
    }

    @Test
    @SuppressWarnings("unchecked")
    void processPendingRollbacks_RollbackFails_ShouldNotAck() throws Exception {
        RecordId recordId = RecordId.of("3-0");
        String payload = "{\"userId\":10}";
        MapRecord<String, Object, Object> streamRecord = buildRecord(recordId, payload);

        RollbackFailedEvent event = RollbackFailedEvent.builder()
                .userId(10L).reason("fail").requiredAction("DELETE /api/users/10")
                .timestamp(Instant.now().toString()).rollbackAttempts(3).build();

        Mockito.when(streamOps.read(
                Mockito.any(Consumer.class),
                Mockito.any(StreamReadOptions.class),
                Mockito.<StreamOffset<String>>any()))
               .thenReturn(Flux.just(streamRecord));

        Mockito.when(objectMapper.readValue(payload, RollbackFailedEvent.class))
               .thenReturn(event);

        stubDeleteChain(Mono.error(new RuntimeException("user-service still down")));

        consumer.processPendingRollbacks();

        Mockito.verify(streamOps, Mockito.never())
               .acknowledge(Mockito.any(), Mockito.any(), Mockito.<RecordId>any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void processPendingRollbacks_MalformedJson_ShouldNotAck() throws Exception {
        RecordId recordId = RecordId.of("4-0");
        String payload = "not-valid-json";
        MapRecord<String, Object, Object> streamRecord = buildRecord(recordId, payload);

        Mockito.when(streamOps.read(
                Mockito.any(Consumer.class),
                Mockito.any(StreamReadOptions.class),
                Mockito.<StreamOffset<String>>any()))
               .thenReturn(Flux.just(streamRecord));

        Mockito.when(objectMapper.readValue(payload, RollbackFailedEvent.class))
               .thenThrow(new JsonProcessingException("bad json") {});

        consumer.processPendingRollbacks();

        Mockito.verify(streamOps, Mockito.never())
               .acknowledge(Mockito.any(), Mockito.any(), Mockito.<RecordId>any());
        Mockito.verify(webClient, Mockito.never()).delete();
    }

    @Test
    @SuppressWarnings("unchecked")
    void processPendingRollbacks_MultipleRecords_EachProcessedIndependently() throws Exception {
        RecordId id1 = RecordId.of("5-0");
        RecordId id2 = RecordId.of("6-0");
        String payload1 = "{\"userId\":1}";
        String payload2 = "{\"userId\":2}";

        MapRecord<String, Object, Object> streamRecord1 = buildRecord(id1, payload1);
        MapRecord<String, Object, Object> streamRecord2 = buildRecord(id2, payload2);

        RollbackFailedEvent event1 = buildEvent(1L);
        RollbackFailedEvent event2 = buildEvent(2L);

        Mockito.when(streamOps.read(
                Mockito.any(Consumer.class),
                Mockito.any(StreamReadOptions.class),
                Mockito.<StreamOffset<String>>any()))
               .thenReturn(Flux.just(streamRecord1, streamRecord2));

        Mockito.when(objectMapper.readValue(payload1, RollbackFailedEvent.class)).thenReturn(event1);
        Mockito.when(objectMapper.readValue(payload2, RollbackFailedEvent.class)).thenReturn(event2);

        stubDeleteChain(Mono.empty());

        Mockito.when(streamOps.acknowledge(Mockito.eq(STREAM_KEY), Mockito.eq(CONSUMER_GROUP),
                Mockito.any(RecordId.class)))
               .thenReturn(Mono.just(1L));

        consumer.processPendingRollbacks();

        Mockito.verify(streamOps).acknowledge(STREAM_KEY, CONSUMER_GROUP, id1);
        Mockito.verify(streamOps).acknowledge(STREAM_KEY, CONSUMER_GROUP, id2);
    }

    private MapRecord<String, Object, Object> buildRecord(RecordId id, String payload) {
        Map<Object, Object> fields = new HashMap<>();
        if (payload != null) fields.put("payload", payload);
        fields.put("timestamp", Instant.now().toString());
        fields.put("userId", "10");
        return (MapRecord<String, Object, Object>)
                MapRecord.create(STREAM_KEY, fields).withId(id);
    }

    private RollbackFailedEvent buildEvent(Long userId) {
        return RollbackFailedEvent.builder()
                .userId(userId)
                .reason("reason")
                .requiredAction("DELETE /api/users/" + userId)
                .timestamp(Instant.now().toString())
                .rollbackAttempts(3)
                .build();
    }

    private void stubDeleteChain(Mono<Void> result) {
        Mockito.doReturn(requestHeadersUriSpec)
            .when(webClient).delete();
        Mockito.doReturn(requestHeadersSpec)
            .when(requestHeadersUriSpec).uri(Mockito.anyString());
        Mockito.doReturn(responseSpec)
            .when(requestHeadersSpec).retrieve();
        Mockito.doReturn(result)
            .when(responseSpec).bodyToMono(Void.class);
    }
}