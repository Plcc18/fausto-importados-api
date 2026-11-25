package com.example.fausto_importados_api.security.ratelimit;

import java.util.function.BiConsumer;

/**
 * Resultado de uma checagem.
 *
 * @param limit             tamanho do balde (máximo de requisições de uma vez)
 * @param remaining         requisições que ainda cabem agora
 * @param retryAfterSeconds se negado, em quantos segundos haverá uma ficha; 0 se permitido
 * @param resetSeconds      em quantos segundos o balde volta a ficar cheio
 */
public record Decision(boolean allowed, int limit, int remaining, long retryAfterSeconds, long resetSeconds) {

    static Decision from(RateLimitStore.Outcome outcome, PropertiesView rule) {
        double perSecond = (double) rule.limit() / (rule.windowMs() / 1000.0);
        int remaining = (int) Math.max(0, Math.floor(outcome.tokens() + TokenBucket.EPSILON));
        long retryAfter = outcome.allowed() ? 0
                : Math.max(1, (long) Math.ceil((1.0 - outcome.tokens()) / perSecond));
        long reset = Math.max(0, (long) Math.ceil((rule.limit() - outcome.tokens()) / perSecond));
        return new Decision(outcome.allowed(), rule.limit(), remaining, retryAfter, reset);
    }

    /** Quando o limitador não pôde decidir (Redis fora do ar): deixa passar, sem informar saldo. */
    static Decision unknown(int limit) {
        return new Decision(true, limit, limit, 0, 0);
    }

    /** Cabeçalhos {@code RateLimit-*} (rascunho IETF httpapi-ratelimit-headers) e, se negado, {@code Retry-After}. */
    public void writeHeaders(BiConsumer<String, String> header) {
        header.accept("RateLimit-Limit", Integer.toString(limit));
        header.accept("RateLimit-Remaining", Integer.toString(remaining));
        header.accept("RateLimit-Reset", Long.toString(resetSeconds));
        if (!allowed) {
            header.accept("Retry-After", Long.toString(retryAfterSeconds));
        }
    }

    /** O mais apertado de dois: o que tem menos requisições sobrando (para os cabeçalhos de resposta). */
    static Decision tighter(Decision a, Decision b) {
        if (a == null) {
            return b;
        }
        return b.remaining() < a.remaining() ? b : a;
    }

    record PropertiesView(int limit, long windowMs) {
    }
}
