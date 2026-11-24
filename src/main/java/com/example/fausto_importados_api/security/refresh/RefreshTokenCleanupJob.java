package com.example.fausto_importados_api.security.refresh;

import com.example.fausto_importados_api.audit.AuditService;
import com.example.fausto_importados_api.security.throttle.LoginThrottle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;

/**
 * Limpeza diária: refresh tokens vencidos (inatividade ou limite absoluto), contadores de login parados e
 * registros de auditoria além da retenção ({@code security.audit.retention}, padrão 1 ano).
 */
@Component
public class RefreshTokenCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenCleanupJob.class);

    private final RefreshTokenService service;
    private final LoginThrottle throttle;
    private final AuditService audit;
    private final Clock clock;
    private final Duration auditRetention;

    public RefreshTokenCleanupJob(
            RefreshTokenService service,
            LoginThrottle throttle,
            AuditService audit,
            Clock clock,
            @Value("${security.audit.retention:P365D}") Duration auditRetention
    ) {
        this.service = service;
        this.throttle = throttle;
        this.audit = audit;
        this.clock = clock;
        this.auditRetention = auditRetention;
    }

    @Scheduled(cron = "${security.auth.cleanup-cron:0 0 3 * * *}")
    public void purgeExpired() {
        int removed = service.purgeExpired();
        if (removed > 0) {
            log.info("Refresh tokens expirados removidos: {}", removed);
        }
        int counters = throttle.purgeStale();
        if (counters > 0) {
            log.info("Contadores de login antigos removidos: {}", counters);
        }
        int trail = audit.purgeOlderThan(clock.instant().minus(auditRetention));
        if (trail > 0) {
            log.info("Registros de auditoria além da retenção removidos: {}", trail);
        }
    }
}
