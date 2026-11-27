package com.example.fausto_importados_api.services.exception;

import com.example.fausto_importados_api.concurrency.ApiProblemException;
import com.example.fausto_importados_api.model.Campaign;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/** 409: o período cruza o de outra campanha publicada com a mesma prioridade. */
public class CampaignConflictException extends ApiProblemException {

    public static final String CODE = "campaign_conflict";

    // as datas da mensagem são lidas por uma pessoa: horário da loja
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
            .withZone(ZoneId.of("America/Fortaleza"));

    /** A campanha que já ocupa o período (campo {@code conflictingCampaign} da resposta). */
    public record ConflictingCampaign(UUID id, String name, Instant startsAt, Instant endsAt) {
    }

    private final transient ConflictingCampaign conflicting;

    public CampaignConflictException(Campaign other) {
        super(HttpStatus.CONFLICT, CODE, ("O período se sobrepõe ao da campanha \"%s\" (%s a %s), que tem a mesma "
                + "prioridade. Mude as datas ou a prioridade, ou desative ou arquive a outra.")
                .formatted(other.getName(), DATE.format(other.getStartsAt()), DATE.format(other.getEndsAt())));
        this.conflicting = new ConflictingCampaign(
                other.getId(), other.getName(), other.getStartsAt(), other.getEndsAt());
    }

    public ConflictingCampaign conflicting() {
        return conflicting;
    }
}
