package com.example.fausto_importados_api.security.refresh;

import com.example.fausto_importados_api.audit.AuditAction;
import com.example.fausto_importados_api.audit.AuditService;
import com.example.fausto_importados_api.config.AuthProperties;
import com.example.fausto_importados_api.model.RefreshToken;
import com.example.fausto_importados_api.model.User;
import com.example.fausto_importados_api.repository.RefreshTokenRepository;
import com.example.fausto_importados_api.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Sessões deslizantes com refresh token opaco e rotação.
 *
 * <ul>
 *   <li>Cada login cria uma <b>família</b> (= sessão). O id da família vai no access token como {@code sid}.</li>
 *   <li>Cada refresh troca o token por um novo e zera o prazo de inatividade, sem passar do limite absoluto.</li>
 *   <li>Reapresentar um token já trocado é tratado como roubo: a família inteira é revogada. Exceção: dentro da
 *       janela de tolerância (requisições concorrentes legítimas) só se emite um novo access token.</li>
 *   <li>A rotação é atômica: a linha do token é travada (SELECT ... FOR UPDATE) dentro da transação.</li>
 * </ul>
 */
@Service
public class RefreshTokenService {

    private static final int TOKEN_BYTES = 32; // 256 bits
    private static final SecureRandom RANDOM = new SecureRandom();

    private final RefreshTokenRepository tokens;
    private final UserRepository users;
    private final AuthProperties props;
    private final AuditService audit;
    private final Clock clock;

    public RefreshTokenService(
            RefreshTokenRepository tokens,
            UserRepository users,
            AuthProperties props,
            AuditService audit,
            Clock clock
    ) {
        this.tokens = tokens;
        this.users = users;
        this.props = props;
        this.audit = audit;
        this.clock = clock;
    }

    /** Sessão recém-criada (login). {@code refreshToken} é o valor em claro, para o cookie. */
    public record NewSession(UUID sessionId, String refreshToken) {
    }

    public sealed interface RefreshResult {
        /** Token rotacionado: devolver novo access token E novo cookie de refresh. */
        record Rotated(User user, UUID sessionId, String refreshToken) implements RefreshResult {
        }

        /** Reuso dentro da janela de tolerância: só novo access token; o cookie atual do browser já é o certo. */
        record Grace(User user, UUID sessionId) implements RefreshResult {
        }

        /** Token inexistente, expirado, revogado ou reutilizado. A causa não é exposta ao cliente. */
        record Rejected() implements RefreshResult {
        }
    }

    @Transactional
    public NewSession startSession(User user, String userAgent, String ip) {
        Instant now = clock.instant();
        UUID familyId = UUID.randomUUID();
        Instant absoluteExpiresAt = now.plus(props.refreshAbsolute());
        String raw = newRawToken();
        tokens.save(newToken(user.getId(), familyId, raw, now, absoluteExpiresAt, userAgent, ip));
        return new NewSession(familyId, raw);
    }

    /**
     * Rotaciona o refresh token. Revogações feitas aqui (detecção de reuso) precisam ser confirmadas,
     * por isso o método devolve {@link RefreshResult.Rejected} em vez de lançar exceção (que faria rollback).
     */
    @Transactional
    public RefreshResult refresh(String rawToken, String userAgent, String ip) {
        if (rawToken == null || rawToken.isBlank()) {
            return new RefreshResult.Rejected();
        }

        Optional<RefreshToken> found = tokens.findByTokenHashForUpdate(hash(rawToken));
        if (found.isEmpty()) {
            return new RefreshResult.Rejected();
        }

        RefreshToken current = found.get();
        Instant now = clock.instant();

        if (current.getRevokedAt() != null) {
            return new RefreshResult.Rejected();
        }

        // Token já trocado: ou é uma requisição concorrente legítima, ou é reuso (roubo).
        if (current.getRotatedAt() != null) {
            return handleReuse(current, now);
        }

        if (!now.isBefore(current.getExpiresAt()) || !now.isBefore(current.getAbsoluteExpiresAt())) {
            return new RefreshResult.Rejected();
        }

        User user = users.findById(current.getUserId()).orElse(null);
        if (user == null || !user.isEnabled()) {
            tokens.revokeFamily(current.getFamilyId(), now);
            return new RefreshResult.Rejected();
        }

        String raw = newRawToken();
        RefreshToken next = newToken(
                current.getUserId(), current.getFamilyId(), raw, now,
                current.getAbsoluteExpiresAt(), userAgent, ip
        );
        tokens.save(next);

        current.setRotatedAt(now);
        current.setLastUsedAt(now);
        current.setReplacedById(next.getId());
        tokens.save(current);

        return new RefreshResult.Rotated(user, current.getFamilyId(), raw);
    }

    private RefreshResult handleReuse(RefreshToken old, Instant now) {
        boolean withinGrace = now.isBefore(old.getRotatedAt().plus(props.refreshGrace()));

        if (withinGrace && old.getReplacedById() != null && now.isBefore(old.getAbsoluteExpiresAt())) {
            Optional<RefreshToken> successor = tokens.findById(old.getReplacedById());
            boolean successorAlive = successor.isPresent()
                    && successor.get().getRevokedAt() == null
                    && now.isBefore(successor.get().getExpiresAt());
            Optional<User> user = users.findById(old.getUserId());
            if (successorAlive && user.isPresent() && user.get().isEnabled()) {
                // Não rotaciona de novo: o token atual da família já foi entregue ao browser pela requisição vencedora.
                return new RefreshResult.Grace(user.get(), old.getFamilyId());
            }
        }

        // Fora da janela: assume roubo e derruba a sessão inteira.
        tokens.revokeFamily(old.getFamilyId(), now);
        audit.recordDeniedAs(users.findById(old.getUserId()).orElse(null), AuditAction.REFRESH_REUSE_DETECTED,
                Map.of("sessionRevoked", true));
        return new RefreshResult.Rejected();
    }

    @Transactional
    public void revokeSession(UUID sessionId) {
        tokens.revokeFamily(sessionId, clock.instant());
    }

    /** Troca de senha / "sair de todos os dispositivos". */
    @Transactional
    public void revokeAllSessions(UUID userId) {
        tokens.revokeAllByUser(userId, clock.instant());
    }

    /** Troca de senha: derruba as OUTRAS sessões (outros aparelhos), mantendo a atual. */
    @Transactional
    public void revokeOtherSessions(UUID userId, UUID currentSessionId) {
        tokens.revokeAllByUserExcept(userId, currentSessionId, clock.instant());
    }

    public boolean isSessionActive(UUID sessionId) {
        return tokens.isSessionActive(sessionId, clock.instant());
    }

    @Transactional
    public int purgeExpired() {
        return tokens.deleteExpired(clock.instant());
    }

    private RefreshToken newToken(
            UUID userId, UUID familyId, String raw, Instant now,
            Instant absoluteExpiresAt, String userAgent, String ip
    ) {
        Instant inactivityLimit = now.plus(props.refreshInactivity());
        RefreshToken t = new RefreshToken();
        t.setId(UUID.randomUUID());
        t.setUserId(userId);
        t.setFamilyId(familyId);
        t.setTokenHash(hash(raw));
        t.setCreatedAt(now);
        t.setLastUsedAt(now);
        // Sessão deslizante: cada token vale pelo prazo de inatividade, mas nunca além do limite absoluto.
        t.setExpiresAt(inactivityLimit.isBefore(absoluteExpiresAt) ? inactivityLimit : absoluteExpiresAt);
        t.setAbsoluteExpiresAt(absoluteExpiresAt);
        t.setUserAgent(truncate(userAgent, 255));
        t.setIp(truncate(ip, 64));
        return t;
    }

    private static String newRawToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String hash(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }
}
