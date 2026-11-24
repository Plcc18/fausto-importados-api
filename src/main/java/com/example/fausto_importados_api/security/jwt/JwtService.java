package com.example.fausto_importados_api.security.jwt;

import com.example.fausto_importados_api.config.AuthProperties;
import com.example.fausto_importados_api.model.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.time.Clock;
import java.time.Duration;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

/**
 * Access token: JWT de vida curta, assinado com o segredo de {@code JWT_SECRET}.
 * Payload mínimo: {@code sub} (id do usuário), {@code role} e {@code sid} (id da sessão).
 */
@Service
public class JwtService {

    static final String PURPOSE = "purpose";
    static final String PURPOSE_MFA_CHALLENGE = "mfa-challenge";
    static final String PURPOSE_REAUTH = "reauth";

    private final SecretKey signingKey;
    private final Duration accessTtl;
    private final Duration mfaChallengeTtl;
    private final Duration reauthTtl;
    private final Clock clock;

    public JwtService(
            @Value("${security.jwt.secret}") String jwtSecret,
            AuthProperties properties,
            Clock clock
    ) {
        this.signingKey = Keys.hmacShaKeyFor(Decoders.BASE64.decode(jwtSecret));
        this.accessTtl = properties.accessTtl();
        this.mfaChallengeTtl = properties.mfaChallengeTtl();
        this.reauthTtl = properties.reauthTtl();
        this.clock = clock;
    }

    /** Reautenticação válida: de quem e para qual sessão. */
    public record ReauthClaims(UUID userId, UUID sessionId) {
    }

    /** Dados que o filtro precisa de um access token já verificado. */
    public record AccessClaims(UUID userId, String role, UUID sessionId) {
    }

    public Duration accessTtl() {
        return accessTtl;
    }

    public String generateAccessToken(User user, UUID sessionId) {
        Date now = Date.from(clock.instant());
        Date expiration = Date.from(clock.instant().plus(accessTtl));
        return Jwts.builder()
                .subject(user.getId().toString())
                .claim("role", user.getRole().name())
                .claim("sid", sessionId.toString())
                .issuedAt(now)
                .expiration(expiration)
                .signWith(signingKey)
                .compact();
    }

    /**
     * Valida assinatura e expiração.
     *
     * @throws JwtException             token inválido, adulterado ou expirado
     * @throws IllegalArgumentException claims malformadas
     */
    public AccessClaims parse(String token) {
        return toAccessClaims(parser().parseSignedClaims(token).getPayload());
    }

    /**
     * Valida a assinatura mas aceita token já expirado. Usado só no logout: quem tem um token com
     * assinatura legítima pode encerrar a PRÓPRIA sessão, mesmo que o access token tenha vencido.
     */
    public Optional<AccessClaims> parseIgnoringExpiry(String token) {
        try {
            return Optional.of(parse(token));
        } catch (ExpiredJwtException e) {
            try {
                return Optional.of(toAccessClaims(e.getClaims()));
            } catch (RuntimeException ignored) {
                return Optional.empty();
            }
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * Desafio emitido depois que a SENHA conferiu e antes do segundo fator. Não é um access token: não tem
     * {@code sid} e carrega {@code purpose}, então o filtro o recusa como Bearer. Serve só para os passos do 2FA.
     */
    public String generateMfaChallenge(User user) {
        Date now = Date.from(clock.instant());
        Date expiration = Date.from(clock.instant().plus(mfaChallengeTtl));
        return Jwts.builder()
                .subject(user.getId().toString())
                .claim(PURPOSE, PURPOSE_MFA_CHALLENGE)
                .issuedAt(now)
                .expiration(expiration)
                .signWith(signingKey)
                .compact();
    }

    /** @return id do usuário do desafio, ou vazio se for inválido, expirado ou de outro tipo de token */
    public Optional<UUID> parseMfaChallenge(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            Claims claims = parser().parseSignedClaims(token).getPayload();
            if (!PURPOSE_MFA_CHALLENGE.equals(claims.get(PURPOSE, String.class))) {
                return Optional.empty();
            }
            return Optional.of(UUID.fromString(claims.getSubject()));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public Duration reauthTtl() {
        return reauthTtl;
    }

    /**
     * Prova de que o administrador acabou de confirmar a identidade com um código 2FA. Presa ao usuário E à sessão
     * ({@code sid}): não serve em outra sessão nem para outra pessoa. Não é um access token (tem {@code purpose}).
     */
    public String generateReauthToken(User user, UUID sessionId) {
        Date now = Date.from(clock.instant());
        Date expiration = Date.from(clock.instant().plus(reauthTtl));
        return Jwts.builder()
                .subject(user.getId().toString())
                .claim("sid", sessionId.toString())
                .claim(PURPOSE, PURPOSE_REAUTH)
                .issuedAt(now)
                .expiration(expiration)
                .signWith(signingKey)
                .compact();
    }

    public Optional<ReauthClaims> parseReauthToken(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            Claims claims = parser().parseSignedClaims(token).getPayload();
            if (!PURPOSE_REAUTH.equals(claims.get(PURPOSE, String.class)) || claims.get("sid") == null) {
                return Optional.empty();
            }
            return Optional.of(new ReauthClaims(
                    UUID.fromString(claims.getSubject()),
                    UUID.fromString(claims.get("sid", String.class))));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public boolean isTokenValid(String token) {
        try {
            parse(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    private JwtParser parser() {
        return Jwts.parser()
                .verifyWith(signingKey)
                .clock(() -> Date.from(clock.instant()))
                .build();
    }

    private AccessClaims toAccessClaims(Claims claims) {
        // Tokens de outra finalidade (desafio de 2FA...) e tokens sem sessão nunca valem como access token.
        if (claims.get(PURPOSE) != null || claims.get("sid") == null || claims.get("role") == null) {
            throw new IllegalArgumentException("Not an access token");
        }
        return new AccessClaims(
                UUID.fromString(claims.getSubject()),
                claims.get("role", String.class),
                UUID.fromString(claims.get("sid", String.class))
        );
    }
}
