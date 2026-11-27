package com.example.fausto_importados_api.services;

import com.example.fausto_importados_api.dto.campaign.CampaignDates;
import com.example.fausto_importados_api.dto.campaign.CampaignInput;
import com.example.fausto_importados_api.dto.campaign.CollectionInput;
import com.example.fausto_importados_api.dto.validation.FieldChecks;
import com.example.fausto_importados_api.model.campaign.CampaignContent;
import com.example.fausto_importados_api.model.campaign.CampaignTheme;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Confere tudo o que vem ao criar ou editar uma campanha (contrato da API, §4). Vale também para rascunho: nada é
 * obrigatório aqui, mas o que vier precisa ser válido. O que publicar exige está em
 * {@link CampaignPublishRequirements}.
 */
@Component
public class CampaignInputValidator {

    private static final int MIN_NAME = 3;
    private static final int MAX_NAME = 80;
    private static final int MAX_DESCRIPTION = 500;
    private static final int MAX_PRIORITY = 100;
    static final Duration MAX_DURATION = Duration.ofDays(120);
    static final String TOO_LONG = "A campanha pode durar no máximo " + MAX_DURATION.toDays() + " dias.";

    private final CampaignContentValidator contentValidator;

    private final CampaignCollectionValidator collectionValidator;

    public CampaignInputValidator(
            CampaignContentValidator contentValidator, CampaignCollectionValidator collectionValidator
    ) {
        this.contentValidator = contentValidator;
        this.collectionValidator = collectionValidator;
    }

    /**
     * @param ownCollections ids das coleções que a campanha já tem (vazio na criação)
     * @return a cópia normalizada, que é a que deve ser gravada
     * @throws com.example.fausto_importados_api.exception.FieldValidationException com todos os campos recusados
     */
    public CampaignInput validate(CampaignInput input, Set<UUID> ownCollections) {
        collectionValidator.rejectDiscounts(input.collections());
        FieldChecks checks = new FieldChecks();

        String name = checks.optionalText("name", input.name(), MAX_NAME);
        if (name != null && name.codePointCount(0, name.length()) < MIN_NAME) {
            checks.reject("name", "Use pelo menos " + MIN_NAME + " caracteres.");
        }
        String description = checks.optionalMultilineText("description", input.description(), MAX_DESCRIPTION);

        int priority = input.priority() == null ? 0 : input.priority();
        if (priority < 0 || priority > MAX_PRIORITY) {
            checks.reject("priority", "Use um número de 0 a " + MAX_PRIORITY + ".");
        }

        Instant startsAt = date("startsAt", input.startsAt(), checks);
        Instant endsAt = date("endsAt", input.endsAt(), checks);
        if (startsAt != null && endsAt != null) {
            if (!endsAt.isAfter(startsAt)) {
                checks.reject("endsAt", "O fim precisa ser depois do início.");
            } else if (Duration.between(startsAt, endsAt).compareTo(MAX_DURATION) > 0) {
                checks.reject("endsAt", TOO_LONG);
            }
        }

        CampaignTheme theme = contentValidator.theme(input.theme(), checks);
        CampaignContent content = contentValidator.content(input.content(), checks);
        List<CollectionInput> collections =
                collectionValidator.collections(input.collections(), ownCollections, checks);

        CampaignInput clean = new CampaignInput(name, description, input.type(), priority, startsAt, endsAt,
                theme, content, collections, input.version());
        checks.throwIfInvalid();
        return clean;
    }

    /** Na precisão do banco. */
    private static Instant date(String field, Instant value, FieldChecks checks) {
        if (value == null) {
            return null;
        }
        if (!CampaignDates.isSupported(value)) {
            checks.reject(field, CampaignDates.OUT_OF_RANGE);
            return null;
        }
        return value.truncatedTo(ChronoUnit.MICROS);
    }
}
