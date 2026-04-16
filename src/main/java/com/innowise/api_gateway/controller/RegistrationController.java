package com.innowise.api_gateway.controller;

import com.innowise.api_gateway.dto.AuthResponse;
import com.innowise.api_gateway.dto.GatewayRegisterRequest;
import com.innowise.api_gateway.service.RegistrationOrchestrator;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@Slf4j
@RestController
@RequestMapping("/api/authentications")
@RequiredArgsConstructor
public class RegistrationController {

    private final RegistrationOrchestrator orchestrator;

    @PostMapping("/register")
    public Mono<ResponseEntity<AuthResponse>> register(
            @Valid @RequestBody GatewayRegisterRequest request) {
        log.info("POST /api/authentications/register login={}", request.getLogin());
        return orchestrator.register(request)
                .map(response -> ResponseEntity.status(HttpStatus.CREATED).body(response));
    }
}