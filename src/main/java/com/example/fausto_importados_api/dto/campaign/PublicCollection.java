package com.example.fausto_importados_api.dto.campaign;

import java.util.List;
import java.util.UUID;

/** Uma coleção como a loja a recebe (contrato §5.4): os produtos só pelo id, já na ordem. */
public record PublicCollection(String title, String slug, int position, List<UUID> productIds) {
}
