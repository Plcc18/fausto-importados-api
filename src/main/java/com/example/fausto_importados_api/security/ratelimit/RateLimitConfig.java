package com.example.fausto_importados_api.security.ratelimit;

import com.example.fausto_importados_api.audit.AuditService;
import com.example.fausto_importados_api.repository.RateLimitBucketRepository;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;

/**
 * Escolhe onde ficam os baldes:
 * <ol>
 *   <li>{@code REDIS_URL} definido: Redis (script Lua atômico), compartilhado entre instâncias;</li>
 *   <li>senão, nos perfis {@code dev} e {@code test}: memória (cada instância conta sozinha);</li>
 *   <li>senão: Postgres, também compartilhado entre instâncias.</li>
 * </ol>
 * {@code security.rate-limit.store} força uma escolha. Pedir {@code redis} sem {@code REDIS_URL} falha na partida.
 */
@Configuration
@EnableConfigurationProperties({RateLimitProperties.class, ProxyProperties.class})
public class RateLimitConfig {

    private static final Logger log = LoggerFactory.getLogger(RateLimitConfig.class);

    // O Spring fecha sozinho (AutoCloseable) o cliente do Redis no shutdown
    @Bean
    public RateLimitStore rateLimitStore(
            RateLimitProperties props,
            Environment environment,
            EntityManager entityManager,
            RateLimitBucketRepository buckets,
            PlatformTransactionManager transactionManager
    ) {
        String mode = props.store() == null ? "auto" : props.store().trim().toLowerCase();
        boolean hasRedis = props.redisUrl() != null && !props.redisUrl().isBlank();

        RateLimitStore store = switch (mode) {
            case "redis" -> {
                if (!hasRedis) {
                    throw new IllegalStateException("security.rate-limit.store=redis requires REDIS_URL");
                }
                yield new RedisRateLimitStore(props.redisUrl());
            }
            case "jdbc" -> new JdbcRateLimitStore(entityManager, buckets, transactionManager);
            case "memory" -> new MemoryRateLimitStore();
            case "auto" -> {
                if (hasRedis) {
                    yield new RedisRateLimitStore(props.redisUrl());
                }
                yield environment.acceptsProfiles(Profiles.of("dev", "test"))
                        ? new MemoryRateLimitStore()
                        : new JdbcRateLimitStore(entityManager, buckets, transactionManager);
            }
            default -> throw new IllegalStateException("security.rate-limit.store invalid: " + props.store());
        };
        log.info("Rate limiting {} (armazenamento: {}, proxies confiáveis: {})",
                props.enabled() ? "ligado" : "DESLIGADO", store.name(), props.trustedProxyHops());
        if (props.enabled() && store instanceof MemoryRateLimitStore) {
            log.warn("Rate limit em MEMÓRIA: cada instância conta sozinha. Use só em desenvolvimento.");
        }
        return store;
    }

    @Bean
    public ProxyGate proxyGate(ProxyProperties props) {
        ProxyGate gate = new ProxyGate(props);
        log.info("Proxy do front (X-Proxy-Secret): {}{}", gate.enabled() ? "ligado" : "desligado",
                props.requireForAuth() ? ", /auth/** exige o segredo" : "");
        return gate;
    }

    @Bean
    public RateLimiter rateLimiter(RateLimitStore store, Clock clock, AuditService audit) {
        return new RateLimiter(store, clock, audit);
    }

    /** Apaga do Postgres os baldes parados há mais de 2 janelas (já estariam cheios). */
    @Bean
    public RateLimitCleanupJob rateLimitCleanupJob(RateLimitStore store, RateLimitProperties props, Clock clock) {
        return new RateLimitCleanupJob(store, props, clock);
    }

    public static class RateLimitCleanupJob {

        private final RateLimitStore store;
        private final RateLimitProperties props;
        private final Clock clock;

        RateLimitCleanupJob(RateLimitStore store, RateLimitProperties props, Clock clock) {
            this.store = store;
            this.props = props;
            this.clock = clock;
        }

        @Scheduled(cron = "${security.rate-limit.cleanup-cron:0 17 * * * *}")
        public void purge() {
            long idleBefore = clock.millis() - 2 * props.maxWindow().toMillis();
            int removed = store.purgeIdleBefore(idleBefore);
            if (removed > 0) {
                log.info("Baldes de rate limit parados removidos: {}", removed);
            }
        }
    }
}
