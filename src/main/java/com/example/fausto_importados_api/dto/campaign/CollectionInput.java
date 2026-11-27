package com.example.fausto_importados_api.dto.campaign;

import java.util.List;
import java.util.UUID;

/**
 * Uma coleção enviada pelo painel.
 *
 * @param id       nulo = coleção nova
 * @param position ordem de exibição; ausente = o lugar dela na lista
 * @param products ausente = nenhum
 */
public record CollectionInput(UUID id, String title, String slug, Integer position, List<Product> products) {

    /**
     * @param position ordem dentro da coleção; ausente = o lugar na lista
     * @param discount reservado para a segunda etapa: só nulo é aceito
     */
    public record Product(UUID productId, Integer position, Object discount) {
    }
}
