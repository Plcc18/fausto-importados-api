package com.example.fausto_importados_api.controller;

import com.example.fausto_importados_api.dto.campaign.ActiveCampaignResponse;
import com.example.fausto_importados_api.services.ActiveCampaignCache;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** A campanha que a loja deve exibir. Público: não exige autenticação. */
@RestController
@RequestMapping("/api/campaigns")
public class PublicCampaignController {

    private final ActiveCampaignCache cache;
    private final Clock clock;

    public PublicCampaignController(ActiveCampaignCache cache, Clock clock) {
        this.cache = cache;
        this.clock = clock;
    }

    /**
     * Com {@code ETag} e cache curto no navegador. Um {@code If-None-Match} igual recebe 304 (o Spring compara).
     *
     * <p>O cabeçalho {@code Date} leva a hora do servidor também no 304, quando o navegador reaproveita o corpo
     * antigo (e o {@code serverTime} dele).
     */
    @GetMapping("/active")
    public ResponseEntity<ActiveCampaignResponse> active() {
        ActiveCampaignCache.Snapshot snapshot = cache.get();
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        return ResponseEntity.ok()
                .eTag(snapshot.etag())
                .cacheControl(CacheControl.maxAge(maxAge(now, snapshot.nextChangeAt())).cachePublic())
                .headers(headers -> headers.setDate(now))
                .body(new ActiveCampaignResponse(now, snapshot.nextChangeAt(), snapshot.campaign()));
    }

    // perto de um início ou fim, o navegador não pode guardar a resposta além dele
    private static Duration maxAge(Instant now, Instant nextChangeAt) {
        if (nextChangeAt == null) {
            return ActiveCampaignCache.TTL;
        }
        Duration untilChange = Duration.between(now, nextChangeAt);
        if (untilChange.isNegative()) {
            return Duration.ZERO;
        }
        return untilChange.compareTo(ActiveCampaignCache.TTL) < 0 ? untilChange : ActiveCampaignCache.TTL;
    }
}
