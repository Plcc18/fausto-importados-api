package com.example.fausto_importados_api.security.ratelimit;

/**
 * Conta do token bucket, compartilhada pela memória e pelo Postgres. O Redis faz a mesma conta em Lua
 * ({@link RedisRateLimitStore}); o teste de contrato confere que os três concordam.
 */
final class TokenBucket {

    /** Tolerância para o erro de ponto flutuante ao recuperar fichas (ex.: 5 fichas em 15 min). */
    static final double EPSILON = 1e-9;

    private TokenBucket() {
    }

    /**
     * Recupera as fichas desde a última vez e tenta gastar uma.
     *
     * @param tokens fichas guardadas (null = balde novo, cheio)
     * @param lastMs quando {@code tokens} foi calculado
     */
    static RateLimitStore.Outcome step(Double tokens, long lastMs, long nowMs, int capacity, long windowMs) {
        double perMs = (double) capacity / windowMs;
        double available = tokens == null
                ? capacity
                : Math.min(capacity, tokens + Math.max(0, nowMs - lastMs) * perMs);
        boolean allowed = available >= 1.0 - EPSILON;
        if (allowed) {
            available = Math.max(0, available - 1.0);
        }
        return new RateLimitStore.Outcome(allowed, available);
    }
}
