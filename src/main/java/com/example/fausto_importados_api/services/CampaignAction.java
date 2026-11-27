package com.example.fausto_importados_api.services;

import com.example.fausto_importados_api.concurrency.ApiProblemException;
import com.example.fausto_importados_api.model.Campaign;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

/**
 * A tabela de transições do contrato da API (§7.3): em que situação cada ação é permitida. Duplicar não está aqui
 * porque vale para qualquer campanha.
 */
public enum CampaignAction {
    EDIT("editar", State.DRAFT, State.SCHEDULED, State.ACTIVE, State.DISABLED),
    PUBLISH("publicar", State.DRAFT),
    ACTIVATE_NOW("ativar", State.DRAFT, State.SCHEDULED),
    DISABLE("desativar", State.SCHEDULED, State.ACTIVE),
    REACTIVATE("reativar", State.DISABLED),
    ARCHIVE("arquivar", State.DRAFT, State.EXPIRED, State.DISABLED),
    DELETE("excluir", State.DRAFT); // o que já foi ao ar é arquivado, para não perder o histórico

    /** O status gravado, com as publicadas separadas pela fase. */
    private enum State {
        DRAFT("em rascunho"),
        SCHEDULED("agendada"),
        ACTIVE("no ar"),
        EXPIRED("encerrada"),
        DISABLED("desativada"),
        ARCHIVED("arquivada");

        private final String description;

        State(String description) {
            this.description = description;
        }

        static State of(Campaign campaign, Instant now) {
            return switch (campaign.getStatus()) {
                case DRAFT -> DRAFT;
                case DISABLED -> DISABLED;
                case ARCHIVED -> ARCHIVED;
                case PUBLISHED -> switch (campaign.phaseAt(now)) {
                    case SCHEDULED -> SCHEDULED;
                    case ACTIVE -> ACTIVE;
                    case EXPIRED -> EXPIRED;
                };
            };
        }
    }

    private final String verb;
    private final Set<State> allowed;

    CampaignAction(String verb, State first, State... others) {
        this.verb = verb;
        this.allowed = EnumSet.of(first, others);
    }

    /** @throws ApiProblemException 409 {@code invalid_transition} */
    public void require(Campaign campaign, Instant now) {
        State state = State.of(campaign, now);
        if (!allowed.contains(state)) {
            throw new ApiProblemException(HttpStatus.CONFLICT, "invalid_transition",
                    "Não é possível %s uma campanha %s.".formatted(verb, state.description));
        }
    }
}
