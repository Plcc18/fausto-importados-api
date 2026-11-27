package com.example.fausto_importados_api.services;

import com.example.fausto_importados_api.dto.campaign.ActiveCampaignResponse;
import com.example.fausto_importados_api.dto.campaign.PublicCampaign;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Objects;

/**
 * A campanha no ar guardada em memória, para as visitas à loja não consultarem o banco.
 *
 * <p>Uma entrada vale até o primeiro destes acontecer:
 * <ul>
 *   <li>{@link #invalidate}, chamado depois de confirmada cada alteração de campanha ou de produto ativo;</li>
 *   <li>o próximo início ou fim que muda a resposta ({@code nextChangeAt});</li>
 *   <li>{@link #TTL}: cobre o que não passa por esta instância (outra instância, ajuste direto no banco).</li>
 * </ul>
 */
@Component
public class ActiveCampaignCache {

    /** Também é o {@code max-age} da rota pública. */
    public static final Duration TTL = Duration.ofSeconds(60);

    /**
     * @param etag identifica a campanha e a próxima mudança; a hora do servidor fica de fora, senão nenhuma resposta
     *             seria igual à anterior
     */
    public record Snapshot(PublicCampaign campaign, Instant nextChangeAt, String etag) {
    }

    private record Entry(Snapshot snapshot, Instant loadedAt, Instant expiresAt) {

        // "antes de carregada" = o relógio voltou: não dá para confiar na validade
        boolean isFresh(Instant now) {
            return !now.isBefore(loadedAt) && now.isBefore(expiresAt);
        }
    }

    private final PublicCampaignService source;
    private final ObjectMapper json;
    private final Clock clock;

    private volatile Entry entry;

    public ActiveCampaignCache(PublicCampaignService source, ObjectMapper json, Clock clock) {
        this.source = source;
        this.json = json;
        this.clock = clock;
    }

    public Snapshot get() {
        Entry current = entry;
        if (current != null && current.isFresh(now())) {
            return current.snapshot();
        }
        synchronized (this) { // uma consulta só, mesmo com muitas visitas chegando juntas
            current = entry;
            if (current == null || !current.isFresh(now())) {
                current = load();
                entry = current;
            }
            return current.snapshot();
        }
    }

    /**
     * Chamar depois do commit. Sincronizado com a carga: uma leitura que começou antes da alteração termina e é
     * descartada aqui, em vez de ser guardada depois.
     */
    public synchronized void invalidate() {
        entry = null;
    }

    private Entry load() {
        ActiveCampaignResponse answer = source.active();
        Instant expiresAt = answer.serverTime().plus(TTL);
        if (answer.nextChangeAt() != null && answer.nextChangeAt().isBefore(expiresAt)) {
            expiresAt = answer.nextChangeAt();
        }
        Snapshot snapshot = new Snapshot(answer.campaign(), answer.nextChangeAt(), etag(answer));
        return new Entry(snapshot, answer.serverTime(), expiresAt);
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    /** Fraco ({@code W/}): o corpo enviado também leva a hora do servidor, que muda a cada resposta. */
    private String etag(ActiveCampaignResponse answer) {
        try {
            String content = json.writeValueAsString(answer.campaign()) + Objects.toString(answer.nextChangeAt());
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
            return "W/\"" + HexFormat.of().formatHex(hash, 0, 16) + "\"";
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Could not compute the campaign ETag", e);
        }
    }
}
