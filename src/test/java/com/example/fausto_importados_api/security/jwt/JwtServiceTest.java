package com.example.fausto_importados_api.security.jwt;

import com.example.fausto_importados_api.config.AuthProperties;
import com.example.fausto_importados_api.model.User;
import com.example.fausto_importados_api.model.enums.Role;
import com.example.fausto_importados_api.support.MutableClock;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceTest {

    private static final String SECRET =
            Base64.getEncoder().encodeToString(
                    "minha-chave-secreta-super-segura-256bits".getBytes()
            );

    private MutableClock clock;
    private JwtService jwtService;
    private User user;
    private UUID sessionId;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        jwtService = new JwtService(SECRET, props(), clock);

        user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail("admin@email.com");
        user.setRole(Role.ADMIN);
        sessionId = UUID.randomUUID();
    }

    private static AuthProperties props() {
        return AuthProperties.defaults();
    }

    @Test
    void generatesTokenWithUserIdRoleAndSessionId() {
        String token = jwtService.generateAccessToken(user, sessionId);

        JwtService.AccessClaims claims = jwtService.parse(token);

        assertThat(claims.userId()).isEqualTo(user.getId());
        assertThat(claims.role()).isEqualTo("ADMIN");
        assertThat(claims.sessionId()).isEqualTo(sessionId);
    }

    @Test
    void payloadHasOnlyTheMinimalClaims() throws Exception {
        String token = jwtService.generateAccessToken(user, sessionId);

        String payload = new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]));
        @SuppressWarnings("unchecked")
        Map<String, Object> claims = new ObjectMapper().readValue(payload, Map.class);

        // nada sensível: sem e-mail, senha ou nome
        assertThat(claims.keySet()).containsExactlyInAnyOrderElementsOf(Set.of("sub", "role", "sid", "iat", "exp"));
        assertThat(payload).doesNotContain("admin@email.com");
    }

    @Test
    void tokenLivesForTheConfiguredAccessTtl() {
        String token = jwtService.generateAccessToken(user, sessionId);

        clock.advance(Duration.ofMinutes(14).plusSeconds(59));
        assertThat(jwtService.isTokenValid(token)).isTrue();

        clock.advance(Duration.ofSeconds(2));
        assertThat(jwtService.isTokenValid(token)).isFalse();
    }

    @Test
    void tamperedTokenIsInvalid() {
        String token = jwtService.generateAccessToken(user, sessionId);

        assertThat(jwtService.isTokenValid(token + "abc")).isFalse();
    }

    @Test
    void tokenSignedWithAnotherSecretIsInvalid() {
        JwtService other = new JwtService(
                Base64.getEncoder().encodeToString("outra-chave-secreta-super-segura-256b".getBytes()),
                props(), clock);

        assertThat(jwtService.isTokenValid(other.generateAccessToken(user, sessionId))).isFalse();
    }

    @Test
    void expiredTokenIsStillReadableForLogoutButTamperedIsNot() {
        String token = jwtService.generateAccessToken(user, sessionId);
        clock.advance(Duration.ofHours(1));

        assertThat(jwtService.isTokenValid(token)).isFalse();
        assertThat(jwtService.parseIgnoringExpiry(token))
                .hasValueSatisfying(c -> assertThat(c.sessionId()).isEqualTo(sessionId));
        assertThat(jwtService.parseIgnoringExpiry(token + "abc")).isEmpty();
        assertThat(jwtService.parseIgnoringExpiry("lixo")).isEmpty();
    }
}
