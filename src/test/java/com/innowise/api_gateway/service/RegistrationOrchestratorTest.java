package com.innowise.api_gateway.service;

import com.innowise.api_gateway.deadletter.RedisDeadLetterQueue;
import com.innowise.api_gateway.dto.GatewayRegisterRequest;
import com.innowise.api_gateway.exception.RegistrationException;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class RegistrationOrchestratorTest {

    private MockWebServer mockWebServer;
    private RegistrationOrchestrator orchestrator;
    private RedisDeadLetterQueue deadLetterQueue;  

    @BeforeEach
    void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();

        String baseUrl = mockWebServer.url("/").toString().replaceAll("/$", "");

        deadLetterQueue = Mockito.mock(RedisDeadLetterQueue.class);

        Mockito.when(deadLetterQueue.publish(Mockito.any()))
               .thenReturn(Mono.empty());

        WebClient webClient = WebClient.builder().build();
        orchestrator = new RegistrationOrchestrator(webClient, baseUrl, baseUrl, deadLetterQueue);
    }

    @AfterEach
    void tearDown() throws IOException {
        mockWebServer.shutdown();
    }

    private GatewayRegisterRequest sampleRequest() {
        return GatewayRegisterRequest.builder()
                .login("alice")
                .password("secret")
                .name("Alice")
                .surname("Rossi")
                .email("alice.rossi@example.com")
                .birthDate(LocalDate.of(1990, 1, 1))
                .build();
    }

    @Test
    void register_HappyPath_ShouldReturnAuthResponse() {
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(201)
                .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .setBody("""
                        {"id":10,"name":"Alice","surname":"Rossi",
                         "email":"alice.rossi@example.com","active":true}
                        """));

        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(201)
                .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .setBody("""
                        {"accessToken":"access-abc","refreshToken":"refresh-xyz"}
                        """));

        StepVerifier.create(orchestrator.register(sampleRequest()))
                .assertNext(response -> {
                    assertThat(response.getAccessToken()).isEqualTo("access-abc");
                    assertThat(response.getRefreshToken()).isEqualTo("refresh-xyz");
                })
                .verifyComplete();

        Mockito.verify(deadLetterQueue, Mockito.never()).publish(Mockito.any());
    }

    @Test
    void register_UserServiceUnavailable_ShouldThrowRegistrationException() {
        mockWebServer.enqueue(new MockResponse().setResponseCode(503));

        StepVerifier.create(orchestrator.register(sampleRequest()))
                .expectErrorMatches(RegistrationException.class::isInstance)
                .verify();

        Mockito.verify(deadLetterQueue, Mockito.never()).publish(Mockito.any());
    }


    @Test
    void register_AuthServiceFails_ShouldRollbackUser() throws InterruptedException {
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(201)
                .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .setBody("""
                        {"id":10,"name":"Alice","surname":"Rossi",
                         "email":"alice.rossi@example.com","active":true}
                        """));

        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(409)
                .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .setBody("""
                        {"message":"Login already taken"}
                        """));

        mockWebServer.enqueue(new MockResponse().setResponseCode(204));

        StepVerifier.create(orchestrator.register(sampleRequest()))
                .expectErrorMatches(RegistrationException.class::isInstance)
                .verify();

        assertThat(mockWebServer.getRequestCount()).isEqualTo(3);

        mockWebServer.takeRequest();
        mockWebServer.takeRequest();

        var rollbackRequest = mockWebServer.takeRequest();
        assertThat(rollbackRequest.getMethod()).isEqualTo("DELETE");
        assertThat(rollbackRequest.getPath()).isEqualTo("/api/users/10");

        Mockito.verify(deadLetterQueue, Mockito.never()).publish(Mockito.any());
    }


    @Test
    void register_AuthFailsAndRollbackExhausted_ShouldPublishToDlq(){
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(201)
                .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .setBody("""
                        {"id":10,"name":"Alice","surname":"Rossi",
                         "email":"alice.rossi@example.com","active":true}
                        """));

        mockWebServer.enqueue(new MockResponse().setResponseCode(500));

        mockWebServer.enqueue(new MockResponse().setResponseCode(500)); 
        mockWebServer.enqueue(new MockResponse().setResponseCode(500));
        mockWebServer.enqueue(new MockResponse().setResponseCode(500)); 
        mockWebServer.enqueue(new MockResponse().setResponseCode(500));

        StepVerifier.create(orchestrator.register(sampleRequest()))
                .expectErrorMatches(RegistrationException.class::isInstance)
                .verify(Duration.ofSeconds(20));

        assertThat(mockWebServer.getRequestCount()).isEqualTo(6);

        Mockito.verify(deadLetterQueue, Mockito.times(1)).publish(
                Mockito.argThat(event -> event.getUserId().equals(10L))
        );
    }
}