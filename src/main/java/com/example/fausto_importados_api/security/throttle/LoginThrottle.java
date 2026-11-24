package com.example.fausto_importados_api.security.throttle;

import com.example.fausto_importados_api.config.AuthProperties;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;

/**
 * Limite de tentativas de login, por conta e por IP, com bloqueio temporário progressivo.
 *
 * <ul>
 *   <li><b>Conta</b> é o controle principal. A chave é o hash do e-mail e inclui e-mails inexistentes, então
 *       o bloqueio não revela se a conta existe. Falhas de senha e de código 2FA somam no mesmo contador.</li>
 *   <li><b>IP</b> é secundário e mais folgado: atrás do proxy da Vercel, o IP que a API enxerga é o da própria
 *       Vercel (compartilhado), então ele só protege de verdade contra ataques diretos à API.</li>
 * </ul>
 *
 * Os contadores ficam no banco (não em memória): sobrevivem a reinícios e valem para qualquer instância.
 */
@Service
public class LoginThrottle {

    private final ThrottleStore store;
    private final AuthProperties.Throttle config;
    private final Clock clock;

    public LoginThrottle(ThrottleStore store, AuthProperties properties, Clock clock) {
        this.store = store;
        this.config = properties.throttle();
        this.clock = clock;
    }

    /** Quanto falta para a conta ou o IP poderem tentar de novo; vazio se nenhum está bloqueado. */
    public Optional<Duration> lockedFor(String email, String ip) {
        Instant now = clock.instant();
        Optional<Instant> account = store.lockedUntil(accountKey(email), now);
        Optional<Instant> byIp = ipKey(ip).flatMap(key -> store.lockedUntil(key, now));

        return java.util.stream.Stream.of(account, byIp)
                .flatMap(Optional::stream)
                .max(Instant::compareTo)
                .map(until -> Duration.between(now, until));
    }

    public void recordFailure(String email, String ip) {
        Instant now = clock.instant();
        register(accountKey(email), config.accountThreshold(), config.accountBaseLock(), now);
        ipKey(ip).ifPresent(key -> register(key, config.ipThreshold(), config.ipBaseLock(), now));
    }

    /** Login completo (senha e 2FA): a conta volta a zero. O contador do IP só se desfaz com o tempo. */
    public void recordSuccess(String email) {
        store.clear(accountKey(email));
    }

    public int purgeStale() {
        Instant now = clock.instant();
        return store.purgeStale(now.minus(Duration.ofDays(1)), now);
    }

    private void register(String key, int threshold, Duration baseLock, Instant now) {
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                store.ensureRow(key, now);
            } catch (DataIntegrityViolationException alreadyCreatedByAnotherRequest) {
                // sem problema: a linha existe, é o que queríamos
            }
            if (store.registerFailure(key, threshold, baseLock, config.maxLock(), config.failureWindow(), now)) {
                return;
            }
            // a linha sumiu entre os dois passos (login bem-sucedido a limpou): recria e tenta de novo
        }
    }

    static String accountKey(String email) {
        String normalized = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(StandardCharsets.UTF_8));
            return "a:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Optional<String> ipKey(String ip) {
        if (ip == null || ip.isBlank()) {
            return Optional.empty();
        }
        String trimmed = ip.trim();
        return Optional.of("i:" + (trimmed.length() > 60 ? trimmed.substring(0, 60) : trimmed));
    }
}
