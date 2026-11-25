package com.example.fausto_importados_api.security.ratelimit;

/**
 * Onde ficam os baldes. A checagem e o gasto da ficha são UMA operação atômica: duas requisições simultâneas
 * nunca leem o mesmo saldo.
 */
public interface RateLimitStore {

    /**
     * @param tokens fichas que sobraram DEPOIS desta tentativa (a fração é a recuperação em andamento)
     */
    record Outcome(boolean allowed, double tokens) {
    }

    /** Tenta gastar uma ficha do balde {@code key} (capacidade {@code capacity}, cheio de novo após {@code windowMs}). */
    Outcome consume(String key, int capacity, long windowMs, long nowMs);

    /** Apaga baldes sem uso desde {@code idleBeforeMs}. Só o Postgres precisa: o Redis expira sozinho. */
    default int purgeIdleBefore(long idleBeforeMs) {
        return 0;
    }

    String name();
}
