package com.example.fausto_importados_api.dto.campaign;

import com.example.fausto_importados_api.model.campaign.CampaignContent;
import com.example.fausto_importados_api.model.campaign.CampaignTheme;
import com.example.fausto_importados_api.model.enums.CampaignPhase;
import com.example.fausto_importados_api.model.enums.CampaignStatus;
import com.example.fausto_importados_api.model.enums.CampaignType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Campanha como o painel a vê. */
public record CampaignDetail(
        UUID id,
        String name,
        String description,
        CampaignType type,
        int priority,
        Instant startsAt,
        Instant endsAt,
        CampaignTheme theme,
        CampaignContent content,
        List<CollectionDetail> collections,
        CampaignStatus status,
        CampaignPhase phase,
        Long version,
        Instant createdAt,
        Instant updatedAt,
        String createdBy,
        String updatedBy,
        Instant publishedAt
) {

    public record CollectionDetail(UUID id, String title, String slug, int position, List<ProductEntry> products) {
    }

    /** @param discount reservado para a segunda etapa: sempre nulo */
    public record ProductEntry(UUID productId, int position, Object discount) {
    }
}
