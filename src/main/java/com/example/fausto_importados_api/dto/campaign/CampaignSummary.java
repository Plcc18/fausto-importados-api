package com.example.fausto_importados_api.dto.campaign;

import com.example.fausto_importados_api.model.enums.CampaignPhase;
import com.example.fausto_importados_api.model.enums.CampaignStatus;
import com.example.fausto_importados_api.model.enums.CampaignType;

import java.time.Instant;
import java.util.UUID;

/** Item da listagem do painel. */
public record CampaignSummary(
        UUID id,
        String name,
        CampaignType type,
        CampaignStatus status,
        CampaignPhase phase,
        int priority,
        Instant startsAt,
        Instant endsAt,
        Instant updatedAt,
        String updatedBy,
        int collectionCount,
        Long version
) {
}
