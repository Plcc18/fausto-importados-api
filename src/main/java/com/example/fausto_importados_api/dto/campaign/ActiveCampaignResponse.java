package com.example.fausto_importados_api.dto.campaign;

import java.time.Instant;

/**
 * Resposta de {@code /api/campaigns/active} e da prévia.
 *
 * @param serverTime   hora do servidor, para o front não confiar no relógio do visitante
 * @param nextChangeAt próximo instante em que a resposta muda sozinha; nulo = nada previsto (e sempre na prévia)
 * @param campaign     nulo = nenhuma campanha no ar: a loja usa o tema padrão
 */
public record ActiveCampaignResponse(Instant serverTime, Instant nextChangeAt, PublicCampaign campaign) {
}
