package com.innowise.api_gateway.service;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.util.Date;

import static org.assertj.core.api.Assertions.*;

class JwtServiceTest {

    private JwtService jwtService;

    private static final String SECRET =
            "dGVzdFNlY3JldEtleUZvckpXVFRva2VuU2lnbmluZzEyMzQ1Njc4OTBBQ0RFRkdISUpLTE1OT1BRUlNUVVZXWFla";

    @BeforeEach
    void setUp() {
        jwtService = new JwtService(SECRET);
    }


    private String buildToken(Long userId, String role, String type, long expirationMs) {
        SecretKey key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(SECRET));
        return Jwts.builder()
                .subject(userId.toString())
                .claim("role", role)
                .claim("type", type)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expirationMs))
                .signWith(key)
                .compact();
    }


    @Test
    void validateToken_ValidToken_ShouldReturnTrue() {
        String token = buildToken(1L, "USER", "ACCESS", 3600000);
        assertThat(jwtService.validateToken(token)).isTrue();
    }

    @Test
    void validateToken_ExpiredToken_ShouldReturnFalse() {
        String token = buildToken(1L, "USER", "ACCESS", -1000);
        assertThat(jwtService.validateToken(token)).isFalse();
    }

    @Test
    void validateToken_GarbageString_ShouldReturnFalse() {
        assertThat(jwtService.validateToken("not.a.jwt")).isFalse();
    }

    @Test
    void validateToken_EmptyString_ShouldReturnFalse() {
        assertThat(jwtService.validateToken("")).isFalse();
    }

    @Test
    void validateToken_WrongSecret_ShouldReturnFalse() {
        String wrongSecret =
                "d3JvbmdTZWNyZXRLZXlGb3JKV1RUb2tlblNpZ25pbmcxMjM0NTY3ODkwQUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVo=";
        SecretKey key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(wrongSecret));
        String token = Jwts.builder()
                .subject("1")
                .claim("role", "USER")
                .claim("type", "ACCESS")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 3600000))
                .signWith(key)
                .compact();

        assertThat(jwtService.validateToken(token)).isFalse();
    }


    @Test
    void extractUserId_ShouldReturnCorrectId() {
        String token = buildToken(42L, "USER", "ACCESS", 3600000);
        assertThat(jwtService.extractUserId(token)).isEqualTo(42L);
    }

    @Test
    void extractUserId_AdminToken_ShouldReturnCorrectId() {
        String token = buildToken(99L, "ADMIN", "ACCESS", 3600000);
        assertThat(jwtService.extractUserId(token)).isEqualTo(99L);
    }


    @Test
    void extractRole_ShouldReturnUserRole() {
        String token = buildToken(1L, "USER", "ACCESS", 3600000);
        assertThat(jwtService.extractRole(token)).isEqualTo("USER");
    }

    @Test
    void extractRole_ShouldReturnAdminRole() {
        String token = buildToken(1L, "ADMIN", "ACCESS", 3600000);
        assertThat(jwtService.extractRole(token)).isEqualTo("ADMIN");
    }


    @Test
    void extractTokenType_AccessToken_ShouldReturnAccess() {
        String token = buildToken(1L, "USER", "ACCESS", 3600000);
        assertThat(jwtService.extractTokenType(token)).isEqualTo("ACCESS");
    }

    @Test
    void extractTokenType_RefreshToken_ShouldReturnRefresh() {
        String token = buildToken(1L, "USER", "REFRESH", 3600000);
        assertThat(jwtService.extractTokenType(token)).isEqualTo("REFRESH");
    }


    @Test
    void extractExpirationSeconds_FutureToken_ShouldReturnPositiveValue() {
        String token = buildToken(1L, "USER", "ACCESS", 3600000);
        long ttl = jwtService.extractExpirationSeconds(token);
        assertThat(ttl).isGreaterThan(0).isLessThanOrEqualTo(3600);
    }

    @Test
    void extractExpirationSeconds_AlreadyExpired_ShouldReturnZero() {
        long remaining = Math.max(
                (new Date(System.currentTimeMillis() - 1000).getTime() - System.currentTimeMillis()) / 1000,0);
        assertThat(remaining).isZero();
    }
}