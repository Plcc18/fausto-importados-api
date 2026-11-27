package com.example.fausto_importados_api.repository;

import com.example.fausto_importados_api.dto.campaign.CampaignFilter;
import com.example.fausto_importados_api.model.Campaign;
import com.example.fausto_importados_api.model.enums.CampaignPhase;
import com.example.fausto_importados_api.model.enums.CampaignStatus;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Busca do painel. */
public final class CampaignSpecifications {

    private CampaignSpecifications() {
    }

    /**
     * Já ordenada (use com uma página sem ordenação): primeiro as sem data de início, depois da mais recente para a
     * mais antiga.
     *
     * @param now instante usado para o filtro por fase
     */
    public static Specification<Campaign> matching(CampaignFilter filter, Instant now) {
        return (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            if (!filter.statuses().isEmpty()) {
                where.add(root.get("status").in(filter.statuses()));
            }
            if (!filter.types().isEmpty()) {
                where.add(root.get("type").in(filter.types()));
            }
            if (!filter.phases().isEmpty()) {
                where.add(cb.or(filter.phases().stream()
                        .map(phase -> inPhase(phase, now, root, cb))
                        .toArray(Predicate[]::new)));
            }
            // campanha sem datas não cruza intervalo nenhum
            if (filter.from() != null) {
                where.add(cb.greaterThan(root.<Instant>get("endsAt"), filter.from()));
            }
            if (filter.to() != null) {
                where.add(cb.lessThan(root.<Instant>get("startsAt"), filter.to()));
            }
            if (filter.q() != null) {
                where.add(cb.like(cb.lower(root.get("name")), contains(filter.q()), '\\'));
            }

            if (Campaign.class.equals(query.getResultType())) { // a contagem da paginação não se ordena
                Path<Instant> start = root.get("startsAt");
                // "nulos primeiro" explícito: o padrão muda de um banco para outro
                query.orderBy(
                        cb.asc(cb.<Integer>selectCase().when(cb.isNull(start), 0).otherwise(1)),
                        cb.desc(start),
                        cb.desc(root.get("createdAt")),
                        cb.asc(root.get("id")));
            }
            return cb.and(where.toArray(Predicate[]::new));
        };
    }

    // os mesmos limites de Campaign.phaseAt
    private static Predicate inPhase(CampaignPhase phase, Instant now, Root<Campaign> root, CriteriaBuilder cb) {
        Path<Instant> start = root.get("startsAt");
        Path<Instant> end = root.get("endsAt");
        Predicate period = switch (phase) {
            case SCHEDULED -> cb.greaterThan(start, now);
            case ACTIVE -> cb.and(cb.lessThanOrEqualTo(start, now), cb.greaterThan(end, now));
            case EXPIRED -> cb.lessThanOrEqualTo(end, now);
        };
        return cb.and(cb.equal(root.get("status"), CampaignStatus.PUBLISHED), period);
    }

    // % e _ digitados pelo usuário são procurados como texto
    private static String contains(String term) {
        String escaped = term.toLowerCase(Locale.ROOT)
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
    }
}
