package com.example.fausto_importados_api.security.ratelimit;

import com.example.fausto_importados_api.audit.AuditAction;
import com.example.fausto_importados_api.audit.AuditService;
import com.example.fausto_importados_api.security.ratelimit.RateLimitProperties.Rule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Aplica uma regra a uma chave, registra em log (e na auditoria) quando o limite é atingido e decide o que fazer
 * se o armazenamento falhar.
 *
 * <p>Se o Redis/Postgres falhar, a requisição PASSA (e o erro vai para o log): derrubar a loja inteira porque o
 * limitador caiu seria pior do que ficar sem limite por alguns minutos. O login continua protegido pelo
 * {@code LoginThrottle} (bloqueio por conta no Postgres).
 */
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    /** Um aviso por balde a cada 60 s: sob ataque, não enche o log (nem a tabela de auditoria) a cada requisição. */
    private static final long LOG_EVERY_MS = 60_000;
    private static final int MAX_TRACKED = 10_000;
    private static final int MAX_KEY = 120;

    private final RateLimitStore store;
    private final Clock clock;
    private final AuditService audit;

    private final Map<String, Long> lastLogged = new ConcurrentHashMap<>();
    private volatile long lastFailureLogMs;

    public RateLimiter(RateLimitStore store, Clock clock, AuditService audit) {
        this.store = store;
        this.clock = clock;
        this.audit = audit;
    }

    /**
     * Gasta uma ficha da regra {@code ruleName} para {@code subject} (IP, usuário, hash...).
     *
     * @param clientIp só para o log
     */
    public Decision check(String ruleName, Rule rule, String subject, String clientIp) {
        String key = key(ruleName, subject);
        long now = clock.millis();
        long windowMs = rule.window().toMillis();
        Decision decision;
        try {
            RateLimitStore.Outcome outcome = store.consume(key, rule.limit(), windowMs, now);
            decision = Decision.from(outcome, new Decision.PropertiesView(rule.limit(), windowMs));
        } catch (RuntimeException e) {
            if (now - lastFailureLogMs > LOG_EVERY_MS) {
                lastFailureLogMs = now;
                log.error("Rate limit store '{}' indisponível; requisições liberadas sem limite: {}",
                        store.name(), e.toString());
            }
            return Decision.unknown(rule.limit());
        }
        if (!decision.allowed()) {
            onLimitReached(ruleName, key, subject, clientIp, rule, decision, now);
        }
        return decision;
    }

    public String storeName() {
        return store.name();
    }

    private void onLimitReached(
            String ruleName, String key, String subject, String clientIp, Rule rule, Decision decision, long now
    ) {
        if (lastLogged.size() > MAX_TRACKED) {
            lastLogged.clear();
        }
        Long previous = lastLogged.get(key);
        if (previous != null && now - previous < LOG_EVERY_MS) {
            return;
        }
        lastLogged.put(key, now);
        // `subject` é IP, id de usuário ou hash: nunca e-mail ou token em texto
        log.warn("Rate limit atingido: rule={} subject={} ip={} limit={} retryAfter={}s",
                ruleName, subject, clientIp, rule, decision.retryAfterSeconds());
        audit.recordDenied(AuditAction.RATE_LIMITED, "RATE_LIMIT", ruleName,
                Map.of("rule", ruleName, "limit", rule.toString()));
    }

    private static String key(String ruleName, String subject) {
        String key = ruleName + ":" + subject;
        return key.length() <= MAX_KEY ? key : ruleName + ":" + RateLimitKeys.sha256Short(subject);
    }
}
