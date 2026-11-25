package com.example.fausto_importados_api.security.ratelimit;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O que TODO armazenamento (memória, Postgres, Redis) precisa garantir. Os três rodam estes mesmos testes: é o
 * que prova que a conta em Lua, a do Java e a do banco concordam.
 */
abstract class RateLimitStoreContract {

    private static final long T0 = 1_800_000_000_000L;
    private static final long MIN = Duration.ofMinutes(1).toMillis();

    abstract RateLimitStore store();

    private static String newKey() {
        return "test:" + UUID.randomUUID();
    }

    private int allowedOf(RateLimitStore store, String key, int attempts, int capacity, long windowMs, long now) {
        int allowed = 0;
        for (int i = 0; i < attempts; i++) {
            if (store.consume(key, capacity, windowMs, now).allowed()) {
                allowed++;
            }
        }
        return allowed;
    }

    @Test
    void allowsExactlyTheCapacity_thenDenies() {
        String key = newKey();

        assertThat(allowedOf(store(), key, 10, 5, 15 * MIN, T0)).isEqualTo(5);
        assertThat(store().consume(key, 5, 15 * MIN, T0).allowed()).isFalse();
    }

    @Test
    void reportsTheRemainingTokens() {
        String key = newKey();

        assertThat(store().consume(key, 5, 15 * MIN, T0).tokens()).isCloseTo(4.0, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(store().consume(key, 5, 15 * MIN, T0).tokens()).isCloseTo(3.0, org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    void recoversGraduallyAndFullyAfterTheWindow() {
        String key = newKey();
        long window = 15 * MIN;
        assertThat(allowedOf(store(), key, 5, 5, window, T0)).isEqualTo(5);

        // 5 fichas em 15 min = 1 a cada 3 min: antes disso, nada
        assertThat(store().consume(key, 5, window, T0 + 2 * MIN).allowed()).isFalse();
        // em 3 min, uma ficha
        assertThat(store().consume(key, 5, window, T0 + 3 * MIN).allowed()).isTrue();
        assertThat(store().consume(key, 5, window, T0 + 3 * MIN).allowed()).isFalse();

        // uma janela inteira sem uso: o balde volta cheio, e não passa de cheio
        long later = T0 + 3 * MIN + window;
        assertThat(allowedOf(store(), key, 20, 5, window, later)).isEqualTo(5);
    }

    @Test
    void keysAreIndependent() {
        String a = newKey();
        String b = newKey();

        assertThat(allowedOf(store(), a, 4, 3, MIN, T0)).isEqualTo(3);
        assertThat(allowedOf(store(), b, 4, 3, MIN, T0)).isEqualTo(3);
    }

    @Test
    void concurrentRequests_neverExceedTheCapacity() throws Exception {
        String key = newKey();
        int capacity = 20;
        int threads = 16;
        int perThread = 6; // 96 tentativas para 20 fichas
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger allowed = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                start.await();
                for (int i = 0; i < perThread; i++) {
                    if (store().consume(key, capacity, MIN, T0).allowed()) {
                        allowed.incrementAndGet();
                    }
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get();
        }
        pool.shutdown();

        // nem uma a mais (corrida entre checar e gastar), nem uma a menos (atualização perdida)
        assertThat(allowed.get()).isEqualTo(capacity);
    }
}
