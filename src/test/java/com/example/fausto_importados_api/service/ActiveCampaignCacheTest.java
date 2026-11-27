package com.example.fausto_importados_api.service;

import com.example.fausto_importados_api.dto.campaign.ActiveCampaignResponse;
import com.example.fausto_importados_api.services.ActiveCampaignCache;
import com.example.fausto_importados_api.services.PublicCampaignService;
import com.example.fausto_importados_api.support.MutableClock;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** O cache sob concorrência e diante de falhas. O comportamento pela rota está em CampaignCacheApiTest. */
class ActiveCampaignCacheTest {

    private static final Instant NOW = Instant.parse("2026-11-20T12:00:00Z");

    private final PublicCampaignService source = mock(PublicCampaignService.class);
    private final ActiveCampaignCache cache = new ActiveCampaignCache(
            source, new ObjectMapper().registerModule(new JavaTimeModule()), new MutableClock(NOW));

    @Test
    void manyVisitsArrivingTogetherReadTheDatabaseOnce() throws Exception {
        when(source.active()).thenAnswer(call -> {
            Thread.sleep(100); // dá tempo de as outras chegarem
            return answer(null);
        });
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> visits = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                visits.add(pool.submit(cache::get));
            }
            for (Future<?> visit : visits) {
                visit.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        verify(source, times(1)).active();
    }

    @Test
    void anInvalidationDuringALoadDiscardsWhatWasBeingRead() throws Exception {
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        Instant stale = NOW.plusSeconds(10);
        Instant fresh = NOW.plusSeconds(20);
        when(source.active()).thenAnswer(call -> {
            reading.countDown();
            finish.await(10, TimeUnit.SECONDS);
            return answer(stale); // leu antes do commit
        }).thenReturn(answer(fresh));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> visit = pool.submit(cache::get);
            assertThat(reading.await(10, TimeUnit.SECONDS)).isTrue();
            Future<?> change = pool.submit(cache::invalidate); // a alteração é confirmada no meio da leitura
            assertThatThrownBy(() -> change.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            finish.countDown();
            visit.get(10, TimeUnit.SECONDS);
            change.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(cache.get().nextChangeAt()).isEqualTo(fresh);
        verify(source, times(2)).active();
    }

    @Test
    void aFailedLoadIsNotKept() {
        when(source.active()).thenThrow(new IllegalStateException("banco fora")).thenReturn(answer(null));

        assertThatThrownBy(cache::get).isInstanceOf(IllegalStateException.class);

        assertThat(cache.get().etag()).isNotBlank();
        assertThat(cache.get()).isSameAs(cache.get());
        verify(source, times(2)).active();
    }

    private static ActiveCampaignResponse answer(Instant nextChangeAt) {
        return new ActiveCampaignResponse(NOW, nextChangeAt, null);
    }
}
