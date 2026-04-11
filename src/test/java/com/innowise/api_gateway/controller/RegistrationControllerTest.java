package com.innowise.api_gateway.controller;

import com.innowise.api_gateway.dto.AuthResponse;
import com.innowise.api_gateway.dto.GatewayRegisterRequest;
import com.innowise.api_gateway.exception.GlobalErrorHandler;
import com.innowise.api_gateway.exception.RegistrationException;
import com.innowise.api_gateway.service.RegistrationOrchestrator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.time.LocalDate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RegistrationControllerTest {

    @Mock
    private RegistrationOrchestrator orchestrator;

    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        webTestClient = WebTestClient
                .bindToController(new RegistrationController(orchestrator))
                .controllerAdvice(new GlobalErrorHandler())
                .build();
    }

    private GatewayRegisterRequest validRequest() {
        return GatewayRegisterRequest.builder()
                .login("john")
                .password("secret")
                .name("John")
                .surname("Doe")
                .email("john@example.com")
                .birthDate(LocalDate.of(1990, 1, 1))
                .build();
    }


    @Test
    void register_ValidRequest_ShouldReturn201WithTokens() {
        AuthResponse authResponse = AuthResponse.builder()
                .accessToken("access-abc")
                .refreshToken("refresh-xyz")
                .build();

        when(orchestrator.register(any(GatewayRegisterRequest.class)))
                .thenReturn(Mono.just(authResponse));

        webTestClient.post().uri("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(validRequest())
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.accessToken").isEqualTo("access-abc")
                .jsonPath("$.refreshToken").isEqualTo("refresh-xyz");
    }


    @Test
    void register_MissingLogin_ShouldReturn400() {
        GatewayRegisterRequest request = validRequest();
        request.setLogin("");

        webTestClient.post().uri("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void register_MissingPassword_ShouldReturn400() {
        GatewayRegisterRequest request = validRequest();
        request.setPassword("");

        webTestClient.post().uri("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void register_InvalidEmail_ShouldReturn400() {
        GatewayRegisterRequest request = validRequest();
        request.setEmail("not-an-email");

        webTestClient.post().uri("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void register_MissingName_ShouldReturn400() {
        GatewayRegisterRequest request = validRequest();
        request.setName("");

        webTestClient.post().uri("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isBadRequest();
    }


    @Test
    void register_UserServiceConflict_ShouldReturn409() {
        when(orchestrator.register(any()))
                .thenReturn(Mono.error(
                        new RegistrationException("Email exists", HttpStatus.CONFLICT)));

        webTestClient.post().uri("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(validRequest())
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.CONFLICT)
                .expectBody()
                .jsonPath("$.message").isEqualTo("Email exists");
    }

    @Test
    void register_AuthServiceFails_ShouldReturn500() {
        when(orchestrator.register(any()))
                .thenReturn(Mono.error(
                        new RegistrationException("Auth failed",
                                HttpStatus.INTERNAL_SERVER_ERROR)));

        webTestClient.post().uri("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(validRequest())
                .exchange()
                .expectStatus().is5xxServerError();
    }

    @Test
    void register_ServiceUnavailable_ShouldReturn503() {
        when(orchestrator.register(any()))
                .thenReturn(Mono.error(
                        new RegistrationException("Down",
                                HttpStatus.SERVICE_UNAVAILABLE)));

        webTestClient.post().uri("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(validRequest())
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }
}