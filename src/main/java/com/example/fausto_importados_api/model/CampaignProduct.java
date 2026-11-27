package com.example.fausto_importados_api.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.util.UUID;

/**
 * Participação de um produto numa coleção. Guarda só o id (a loja cruza com o catálogo que já carregou); a existência
 * do produto é garantida pela chave estrangeira.
 */
@Embeddable
public record CampaignProduct(
        @Column(name = "product_id", nullable = false) UUID productId,
        @Column(name = "position", nullable = false) int position
) {
}
