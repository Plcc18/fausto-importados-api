package com.example.fausto_importados_api.dto.campaign;

import com.example.fausto_importados_api.dto.validation.FieldChecks;
import com.example.fausto_importados_api.model.enums.CampaignPhase;
import com.example.fausto_importados_api.model.enums.CampaignStatus;
import com.example.fausto_importados_api.model.enums.CampaignType;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Filtros da listagem. Conjunto vazio ou valor nulo = sem filtro.
 *
 * @param from início do intervalo: ficam as campanhas cujo período o cruza
 * @param to   fim do intervalo (exclusivo)
 * @param q    trecho do nome
 */
public record CampaignFilter(
        Set<CampaignStatus> statuses,
        Set<CampaignPhase> phases,
        Set<CampaignType> types,
        Instant from,
        Instant to,
        String q
) {

    /**
     * Lê os parâmetros da URL ({@code status}, {@code phase} e {@code type} aceitam vários, separados por vírgula).
     *
     * @throws com.example.fausto_importados_api.exception.FieldValidationException com o nome de cada parâmetro inválido
     */
    public static CampaignFilter parse(String status, String phase, String type, String from, String to, String q) {
        FieldChecks checks = new FieldChecks();
        CampaignFilter filter = new CampaignFilter(
                values("status", status, CampaignStatus.class, checks),
                values("phase", phase, CampaignPhase.class, checks),
                values("type", type, CampaignType.class, checks),
                instant("from", from, checks),
                instant("to", to, checks),
                FieldChecks.blankToNull(q));
        if (filter.from() != null && filter.to() != null && !filter.to().isAfter(filter.from())) {
            checks.reject("to", "O fim do intervalo precisa ser depois do início.");
        }
        checks.throwIfInvalid();
        return filter;
    }

    private static <E extends Enum<E>> Set<E> values(String field, String raw, Class<E> type, FieldChecks checks) {
        Set<E> values = EnumSet.noneOf(type);
        if (FieldChecks.blankToNull(raw) == null) {
            return values;
        }
        for (String item : raw.split(",")) {
            try {
                values.add(Enum.valueOf(type, item.strip()));
            } catch (IllegalArgumentException unknown) {
                checks.reject(field, "Valor não permitido. Use um destes: " + Arrays.stream(type.getEnumConstants())
                        .map(Enum::name).collect(Collectors.joining(", ")) + ".");
                break;
            }
        }
        return values;
    }

    private static Instant instant(String field, String raw, FieldChecks checks) {
        String value = FieldChecks.blankToNull(raw);
        if (value == null) {
            return null;
        }
        try {
            Instant instant = Instant.parse(value);
            if (CampaignDates.isSupported(instant)) {
                return instant;
            }
            checks.reject(field, CampaignDates.OUT_OF_RANGE);
        } catch (DateTimeParseException invalid) {
            checks.reject(field, "Use uma data com fuso, como 2026-11-27T00:00:00-03:00.");
        }
        return null;
    }
}
