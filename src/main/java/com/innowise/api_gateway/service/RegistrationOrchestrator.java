package com.innowise.api_gateway.service;

import com.innowise.api_gateway.dto.AuthResponse;
import com.innowise.api_gateway.dto.GatewayRegisterRequest;
import com.innowise.api_gateway.dto.RegisterRequest;
import com.innowise.api_gateway.dto.UserDTO;
import com.innowise.api_gateway.exception.RegistrationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

@Slf4j
@Service
public class RegistrationOrchestrator {

    private final WebClient webClient;
    private final String userServiceUrl;
    private final String authServiceUrl;

    public RegistrationOrchestrator(
            WebClient webClient,
            @Value("${services.user.url}") String userServiceUrl,
            @Value("${services.auth.url}") String authServiceUrl) {
        this.webClient = webClient;
        this.userServiceUrl = userServiceUrl;
        this.authServiceUrl = authServiceUrl;
    }

    public Mono<AuthResponse> register(GatewayRegisterRequest request) {
        return createUser(request)
                .flatMap(createdUser -> {
                    log.info("User created with id={}, proceeding to auth registration",
                            createdUser.getId());
                    return registerCredentials(request, createdUser.getId())
                            .onErrorResume(ex -> {
                                log.error("Auth registration failed for userId={}, initiating rollback",
                                        createdUser.getId());
                                return rollbackUser(createdUser.getId())
                                        .then(Mono.error(toRegistrationException(ex,
                                                "Auth service registration failed — user creation rolled back")));
                            });
                });
    }

    private Mono<UserDTO> createUser(GatewayRegisterRequest request) {
        UserDTO userDTO = UserDTO.builder()
                .name(request.getName())
                .surname(request.getSurname())
                .email(request.getEmail())
                .birthDate(request.getBirthDate())
                .active(true)
                .build();

        return webClient.post()
                .uri(userServiceUrl + "/api/users")
                .bodyValue(userDTO)
                .retrieve()
                .bodyToMono(UserDTO.class)
                .doOnSuccess(u -> log.info("User service responded with id={}", u.getId()))
                .onErrorResume(WebClientResponseException.class, ex ->
                        Mono.error(new RegistrationException(
                                "User service error: " + ex.getResponseBodyAsString(),
                                HttpStatus.valueOf(ex.getStatusCode().value()))))
                .onErrorResume(Exception.class, ex ->
                        Mono.error(new RegistrationException(
                                "User service unavailable", HttpStatus.SERVICE_UNAVAILABLE)));
    }

    private Mono<AuthResponse> registerCredentials(GatewayRegisterRequest request, Long userId) {
        RegisterRequest registerRequest = RegisterRequest.builder()
                .login(request.getLogin())
                .password(request.getPassword())
                .userId(userId)
                .build();

        return webClient.post()
                .uri(authServiceUrl + "/api/auth/register")
                .bodyValue(registerRequest)
                .retrieve()
                .bodyToMono(AuthResponse.class)
                .doOnSuccess(r -> log.info("Auth registration successful for userId={}", userId));
    }

    private Mono<Void> rollbackUser(Long userId) {
        return webClient.delete()
                .uri(userServiceUrl + "/api/users/" + userId)
                .retrieve()
                .bodyToMono(Void.class)
                .doOnSuccess(v -> log.info("Rollback successful — deleted userId={}", userId))
                .onErrorResume(ex -> {
                    log.error("Rollback FAILED for userId={}: {}", userId, ex.getMessage());
                    return Mono.empty(); 
                });
    }

    private RegistrationException toRegistrationException(Throwable ex, String fallbackMessage) {
        if (ex instanceof WebClientResponseException wcEx) {
            return new RegistrationException(wcEx.getResponseBodyAsString(),
                    HttpStatus.valueOf(wcEx.getStatusCode().value()));
        }
        if (ex instanceof RegistrationException re) {
            return re;
        }
        return new RegistrationException(fallbackMessage, HttpStatus.INTERNAL_SERVER_ERROR);
    }
}