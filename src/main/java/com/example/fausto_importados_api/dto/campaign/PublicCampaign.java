package com.example.fausto_importados_api.dto.campaign;

import com.example.fausto_importados_api.model.campaign.CampaignContent;
import com.example.fausto_importados_api.model.campaign.CampaignTheme;
import com.example.fausto_importados_api.model.enums.CampaignType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * O que a loja precisa para exibir uma campanha (contrato §5.4). <b>Qualquer campo acrescentado aqui fica visível a
 * qualquer visitante</b>: nome, descrição, prioridade, status, versão e autores não entram.
 *
 * <p>No ar, {@code type}, {@code startsAt}, {@code endsAt} e {@code theme} sempre vêm preenchidos; na prévia de um
 * rascunho podem ser nulos. {@code content} pode ser nulo nos dois casos.
 */
public record PublicCampaign(
        UUID id,
        CampaignType type,
        Instant startsAt,
        Instant endsAt,
        Instant updatedAt,
        CampaignTheme theme,
        CampaignContent content,
        List<PublicCollection> collections
) {
}
