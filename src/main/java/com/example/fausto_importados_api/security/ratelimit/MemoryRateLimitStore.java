package com.example.fausto_importados_api.security.ratelimit;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Baldes em memória, só para desenvolvimento e testes: cada instância tem os seus, então com várias instâncias o
 * limite real seria multiplicado pelo número delas. Em produção use Redis ou Postgres.
 */
public class MemoryRateLimitStore implements RateLimitStore {

    private static final int SWEEP_THRESHOLD = 50_000;

    private record State(double tokens, long lastMs, long windowMs) {
    }

    private final ConcurrentHashMap<String, State> buckets = new ConcurrentHashMap<>();

    @Override
    public Outcome consume(String key, int capacity, long windowMs, long nowMs) {
        Outcome[] result = new Outcome[1];
        buckets.compute(key, (k, state) -> {
            result[0] = state == null
                    ? TokenBucket.step(null, nowMs, nowMs, capacity, windowMs)
                    : TokenBucket.step(state.tokens(), state.lastMs(), nowMs, capacity, windowMs);
            return new State(result[0].tokens(), nowMs, windowMs);
        });
        if (buckets.size() > SWEEP_THRESHOLD) {
            sweep(nowMs);
        }
        return result[0];
    }

    /** Remove baldes que já voltaram a ficar cheios (equivalem a um balde novo). */
    private void sweep(long nowMs) {
        buckets.entrySet().removeIf(e -> nowMs - e.getValue().lastMs() >= e.getValue().windowMs());
    }

    @Override
    public String name() {
        return "memory";
    }
}
