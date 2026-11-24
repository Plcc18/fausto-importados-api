package com.example.fausto_importados_api.security.refresh;

import com.example.fausto_importados_api.security.throttle.LoginThrottle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Limpeza diária: refresh tokens vencidos (inatividade ou limite absoluto) e contadores de login parados. */
@Component
public class RefreshTokenCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenCleanupJob.class);

    private final RefreshTokenService service;
    private final LoginThrottle throttle;

    public RefreshTokenCleanupJob(RefreshTokenService service, LoginThrottle throttle) {
        this.service = service;
        this.throttle = throttle;
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
    }
}
