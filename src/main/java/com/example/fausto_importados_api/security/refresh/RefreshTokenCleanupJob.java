package com.example.fausto_importados_api.security.refresh;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Remove diariamente refresh tokens vencidos (por inatividade ou pelo limite absoluto). */
@Component
public class RefreshTokenCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenCleanupJob.class);

    private final RefreshTokenService service;

    public RefreshTokenCleanupJob(RefreshTokenService service) {
        this.service = service;
    }

    @Scheduled(cron = "${security.auth.cleanup-cron:0 0 3 * * *}")
    public void purgeExpired() {
        int removed = service.purgeExpired();
        if (removed > 0) {
            log.info("Refresh tokens expirados removidos: {}", removed);
        }
    }
}
