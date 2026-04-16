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
import reactor.util.retry.Retry;

import java.time.Duration;

@Slf4j
@Service
public class RegistrationOrchestrator {

    private final WebClient webClient;
    private final String userServiceUrl;
    private final String authServiceUrl;

    private static final int ROLLBACK_MAX_ATTEMPTS = 3;
    private static final Duration ROLLBACK_RETRY_BACKOFF = Duration.ofSeconds(2);

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
                .uri(authServiceUrl + "/api/authentications/register")
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
                .retryWhen(Retry.backoff(ROLLBACK_MAX_ATTEMPTS, ROLLBACK_RETRY_BACKOFF)
                        .filter(this::isRetryable)
                        .doBeforeRetry(signal -> log.warn(
                                "Rollback attempt {} failed for userId={}, retrying. Cause: {}",
                                signal.totalRetries() + 1,
                                userId,
                                signal.failure().getMessage()))
                        .onRetryExhaustedThrow((spec, signal) -> signal.failure()))
                .onErrorResume(ex -> {
                        publishRollbackAlert(userId, ex);
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

        private boolean isRetryable(Throwable ex) {
                if (ex instanceof WebClientResponseException wcEx) {
                        return wcEx.getStatusCode().is5xxServerError();
                }
                return ex instanceof java.io.IOException
                        || ex instanceof reactor.netty.http.client.PrematureCloseException;
        }

        private void publishRollbackAlert(Long userId, Throwable ex) {
                log.error("""
                        [ROLLBACK_FAILED] MANUAL INTERVENTION REQUIRED
                        Orphaned user record detected.
                        userId    : {}
                        reason    : {}
                        action    : DELETE /api/users/{} must be executed manually
                        """,
                        userId,
                        ex.getMessage(),
                        userId);
        }
                
}