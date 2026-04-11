package com.innowise.api_gateway.filter;

import com.innowise.api_gateway.service.JwtService;
import com.innowise.api_gateway.service.TokenBlacklistService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter implements GlobalFilter, Ordered {

    private final JwtService jwtService;
    private final TokenBlacklistService tokenBlacklistService;
    private final ObjectMapper objectMapper;

    @Value("${gateway.public-paths}")
    private List<String> publicPaths;

    @Override
    public int getOrder() {
        return -100;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();

        if (isPublicPath(path)) {
            return chain.filter(exchange);
        }

        String authHeader = exchange.getRequest().getHeaders().getFirst("Authorization");

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            log.warn("Missing or invalid Authorization header for path: {}", path);
            return writeErrorResponse(exchange, HttpStatus.UNAUTHORIZED,
                    "Authentication required");
        }

        String token = authHeader.substring(7);

        return tokenBlacklistService.isBlacklisted(token)
                .flatMap(blacklisted -> {
                    if (Boolean.TRUE.equals(blacklisted)) {
                        log.warn("Blacklisted token used for path: {}", path);
                        return writeErrorResponse(exchange, HttpStatus.UNAUTHORIZED,
                                "Token has been invalidated");
                    }

                    if (!jwtService.validateToken(token)) {
                        log.warn("Invalid or expired token for path: {}", path);
                        return writeErrorResponse(exchange, HttpStatus.UNAUTHORIZED,
                                "Invalid or expired token");
                    }

                    String tokenType = jwtService.extractTokenType(token);
                    if (!"ACCESS".equals(tokenType)) {
                        return writeErrorResponse(exchange, HttpStatus.UNAUTHORIZED,
                                "Only access tokens are accepted");
                    }

                    Long userId = jwtService.extractUserId(token);
                    String role = jwtService.extractRole(token);

                    ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                            .header("X-User-Id", userId.toString())
                            .header("X-User-Role", role)
                            .build();

                    log.debug("Authenticated request: userId={} role={} path={}", userId, role, path);
                    return chain.filter(exchange.mutate().request(mutatedRequest).build());
                });
    }

    private boolean isPublicPath(String path) {
        return publicPaths.stream().anyMatch(path::startsWith);
    }

    private Mono<Void> writeErrorResponse(ServerWebExchange exchange,
                                          HttpStatus status, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> body = Map.of(
                "timestamp", LocalDateTime.now().toString(),
                "status", status.value(),
                "error", status.getReasonPhrase(),
                "message", message
        );

        try {
            byte[] bytes = objectMapper.writeValueAsBytes(body);
            DataBuffer buffer = response.bufferFactory().wrap(bytes);
            return response.writeWith(Mono.just(buffer));
        } catch (Exception e) {
            return response.setComplete();
        }
    }
}