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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
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
 *
 * <p>Toda conferência de credencial começa por {@link #reserve}: a tentativa é contada ANTES de testar a senha ou o
 * código, e devolvida ({@link Attempt#release}) se não foi um palpite errado.
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

    /**
     * Uma tentativa de credencial. Se {@link #blockedFor} vier preenchido, a credencial não deve ser testada. Não
     * fazer nada com a tentativa equivale a registrar uma falha.
     */
    public final class Attempt {

        private final Duration blockedFor;
        private final List<ThrottleStore.Slot> slots;

        private Attempt(Duration blockedFor, List<ThrottleStore.Slot> slots) {
            this.blockedFor = blockedFor;
            this.slots = slots;
        }

        public Optional<Duration> blockedFor() {
            return Optional.ofNullable(blockedFor);
        }

        /** A tentativa não foi um palpite errado: sai da contagem da conta e do IP. */
        public void release() {
            slots.forEach(store::release);
        }
    }

    /** Conta a tentativa na conta e no IP, a menos que um dos dois esteja bloqueado. */
    public Attempt reserve(String email, String ip) {
        // milissegundos: o instante precisa voltar do banco idêntico para o bloqueio ser reconhecido no release
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        List<ThrottleStore.Slot> counted = new ArrayList<>(2);

        ThrottleStore.Slot account = register(
                accountKey(email), config.accountThreshold(), config.accountBaseLock(), now);
        if (account.blockedUntil() != null) {
            return blocked(email, ip, account, counted, now);
        }
        counted.add(account);

        Optional<String> ipKey = ipKey(ip);
        if (ipKey.isPresent()) {
            ThrottleStore.Slot byIp = register(ipKey.get(), config.ipThreshold(), config.ipBaseLock(), now);
            if (byIp.blockedUntil() != null) {
                return blocked(email, ip, byIp, counted, now);
            }
            counted.add(byIp);
        }
        return new Attempt(null, counted);
    }

    private Attempt blocked(
            String email, String ip, ThrottleStore.Slot slot, List<ThrottleStore.Slot> counted, Instant now
    ) {
        counted.forEach(store::release);
        Duration wait = lockedFor(email, ip).orElseGet(() -> Duration.between(now, slot.blockedUntil()));
        return new Attempt(wait, List.of());
    }

    /** Login completo (senha e 2FA): a conta volta a zero. O contador do IP só se desfaz com o tempo. */
    public void recordSuccess(String email) {
        store.clear(accountKey(email));
    }

    public int purgeStale() {
        Instant now = clock.instant();
        return store.purgeStale(now.minus(Duration.ofDays(1)), now);
    }

    private ThrottleStore.Slot register(String key, int threshold, Duration baseLock, Instant now) {
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                store.ensureRow(key, now);
            } catch (DataIntegrityViolationException alreadyCreatedByAnotherRequest) {
                // sem problema: a linha existe, é o que queríamos
            }
            Optional<ThrottleStore.Slot> slot = store.reserve(
                    key, threshold, baseLock, config.maxLock(), config.failureWindow(), now);
            if (slot.isPresent()) {
                return slot.get();
            }
            // a linha sumiu entre os dois passos (login bem-sucedido a limpou): recria e tenta de novo
        }
        throw new IllegalStateException("Could not count a login attempt");
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
