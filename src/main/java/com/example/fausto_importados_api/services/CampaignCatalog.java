package com.example.fausto_importados_api.services;

import com.example.fausto_importados_api.dto.campaign.PublicCollection;
import com.example.fausto_importados_api.model.Campaign;
import com.example.fausto_importados_api.model.CampaignCollection;
import com.example.fausto_importados_api.model.CampaignProduct;
import com.example.fausto_importados_api.repository.ProductRepository;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** O que a loja pode mostrar dos produtos de uma campanha. */
@Component
public class CampaignCatalog {

    private final ProductRepository products;

    public CampaignCatalog(ProductRepository products) {
        this.products = products;
    }

    /**
     * Coleções na ordem de exibição, cada uma só com os produtos ativos. Um produto desativado continua na campanha e
     * volta a aparecer se for reativado. Uma coleção pode ficar vazia.
     *
     * @param campaign com as coleções e os produtos já carregados, ou dentro de uma transação
     */
    public List<PublicCollection> publicCollections(Campaign campaign) {
        List<UUID> ids = campaign.getCollections().stream()
                .flatMap(collection -> collection.getProducts().stream())
                .map(CampaignProduct::productId)
                .distinct()
                .toList();
        Set<UUID> active = ids.isEmpty() ? Set.of() : products.findActiveIds(ids);

        return campaign.getCollections().stream()
                .sorted(Comparator.comparingInt(CampaignCollection::getPosition))
                .map(collection -> new PublicCollection(
                        collection.getTitle(), collection.getSlug(), collection.getPosition(),
                        collection.getProducts().stream()
                                .sorted(Comparator.comparingInt(CampaignProduct::position))
                                .map(CampaignProduct::productId)
                                .filter(active::contains)
                                .toList()))
                .toList();
    }
}
