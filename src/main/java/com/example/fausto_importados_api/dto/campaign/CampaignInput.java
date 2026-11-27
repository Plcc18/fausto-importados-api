package com.example.fausto_importados_api.dto.campaign;

import com.example.fausto_importados_api.model.campaign.CampaignContent;
import com.example.fausto_importados_api.model.campaign.CampaignTheme;
import com.example.fausto_importados_api.model.enums.CampaignType;

import java.time.Instant;
import java.util.List;

/**
 * Corpo de criar e editar. A edição substitui o documento inteiro: o que não vier fica vazio.
 *
 * @param priority    ausente = 0
 * @param collections ausente = nenhuma
 * @param version     versão lida antes de editar (ignorada na criação)
 */
public record CampaignInput(
        String name,
        String description,
        CampaignType type,
        Integer priority,
        Instant startsAt,
        Instant endsAt,
        CampaignTheme theme,
        CampaignContent content,
        List<CollectionInput> collections,
        Long version
) {
}
