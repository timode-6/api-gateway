package com.innowise.api_gateway.filter;

import com.innowise.api_gateway.service.JwtService;
import com.innowise.api_gateway.service.TokenBlacklistService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {

    @Mock
    private JwtService jwtService;

    @Mock
    private TokenBlacklistService tokenBlacklistService;

    @Mock
    private GatewayFilterChain filterChain;

    private JwtAuthenticationFilter filter;

    private static final List<String> PUBLIC_PATHS = List.of(
            "/api/auth/register",
            "/api/auth/token",
            "/api/auth/token/refresh",
            "/api/auth/token/validate"
    );

    @BeforeEach
    void setUp() {
        filter = new JwtAuthenticationFilter(
            jwtService, tokenBlacklistService, new ObjectMapper());
    ReflectionTestUtils.setField(filter, "publicPaths", PUBLIC_PATHS);
    lenient().when(filterChain.filter(any())).thenReturn(Mono.empty());
    }


    @Test
    void publicPath_Register_ShouldPassWithoutTokenCheck() {
        MockServerWebExchange exchange = exchange("/api/auth/register", null);

        StepVerifier.create(filter.filter(exchange, filterChain))
                .verifyComplete();

        verifyNoInteractions(jwtService, tokenBlacklistService);
        verify(filterChain).filter(any());
    }

    @Test
    void publicPath_Token_ShouldPassWithoutTokenCheck() {
        MockServerWebExchange exchange = exchange("/api/auth/token", null);

        StepVerifier.create(filter.filter(exchange, filterChain))
                .verifyComplete();

        verifyNoInteractions(jwtService, tokenBlacklistService);
    }

    @Test
    void publicPath_TokenRefresh_ShouldPassWithoutTokenCheck() {
        MockServerWebExchange exchange = exchange("/api/auth/token/refresh", null);

        StepVerifier.create(filter.filter(exchange, filterChain))
                .verifyComplete();

        verifyNoInteractions(jwtService, tokenBlacklistService);
    }


    @Test
    void noAuthHeader_ShouldReturn401() {
        MockServerWebExchange exchange = exchange("/api/users/1", null);

        StepVerifier.create(filter.filter(exchange, filterChain))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verifyNoInteractions(filterChain);
    }

    @Test
    void basicAuthHeader_ShouldReturn401() {
        MockServerWebExchange exchange = exchange("/api/users/1", "Basic dXNlcjpwYXNz");

        StepVerifier.create(filter.filter(exchange, filterChain))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void blacklistedToken_ShouldReturn401() {
        when(tokenBlacklistService.isBlacklisted("bl-token")).thenReturn(Mono.just(true));
        MockServerWebExchange exchange = exchange("/api/users/1", "Bearer bl-token");

        StepVerifier.create(filter.filter(exchange, filterChain))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verifyNoInteractions(filterChain);
    }


    @Test
    void invalidToken_ShouldReturn401() {
        when(tokenBlacklistService.isBlacklisted("bad-token")).thenReturn(Mono.just(false));
        when(jwtService.validateToken("bad-token")).thenReturn(false);
        MockServerWebExchange exchange = exchange("/api/users/1", "Bearer bad-token");

        StepVerifier.create(filter.filter(exchange, filterChain))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verifyNoInteractions(filterChain);
    }


    @Test
    void refreshToken_ShouldReturn401() {
        when(tokenBlacklistService.isBlacklisted("ref-token")).thenReturn(Mono.just(false));
        when(jwtService.validateToken("ref-token")).thenReturn(true);
        when(jwtService.extractTokenType("ref-token")).thenReturn("REFRESH");
        MockServerWebExchange exchange = exchange("/api/users/1", "Bearer ref-token");

        StepVerifier.create(filter.filter(exchange, filterChain))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verifyNoInteractions(filterChain);
    }


    @Test
    void validAccessToken_ShouldForwardWithUserHeaders() {
        when(tokenBlacklistService.isBlacklisted("good-token")).thenReturn(Mono.just(false));
        when(jwtService.validateToken("good-token")).thenReturn(true);
        when(jwtService.extractTokenType("good-token")).thenReturn("ACCESS");
        when(jwtService.extractUserId("good-token")).thenReturn(5L);
        when(jwtService.extractRole("good-token")).thenReturn("USER");
        MockServerWebExchange exchange = exchange("/api/users/5", "Bearer good-token");

        StepVerifier.create(filter.filter(exchange, filterChain))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isNotEqualTo(HttpStatus.UNAUTHORIZED);
        verify(filterChain).filter(any());
    }

    
    @Test
    void validAdminToken_ShouldAlsoPassThrough() {
        when(tokenBlacklistService.isBlacklisted("admin-token")).thenReturn(Mono.just(false));
        when(jwtService.validateToken("admin-token")).thenReturn(true);
        when(jwtService.extractTokenType("admin-token")).thenReturn("ACCESS");
        when(jwtService.extractUserId("admin-token")).thenReturn(1L);
        when(jwtService.extractRole("admin-token")).thenReturn("ADMIN");
        MockServerWebExchange exchange = exchange("/api/users", "Bearer admin-token");

        StepVerifier.create(filter.filter(exchange, filterChain))
                .verifyComplete();

        verify(filterChain).filter(any());
    }


    @Test
    void filterOrder_ShouldBeMinus100() {
        assertThat(filter.getOrder()).isEqualTo(-100);
    }


    private MockServerWebExchange exchange(String path, String authHeader) {
        MockServerHttpRequest.BaseBuilder<?> builder =
                MockServerHttpRequest.get(path);
        if (authHeader != null) {
            builder.header(HttpHeaders.AUTHORIZATION, authHeader);
        }
        return MockServerWebExchange.from(builder.build());
    }
}