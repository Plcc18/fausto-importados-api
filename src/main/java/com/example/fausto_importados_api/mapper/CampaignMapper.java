package com.example.fausto_importados_api.mapper;

import com.example.fausto_importados_api.dto.campaign.CampaignDetail;
import com.example.fausto_importados_api.dto.campaign.CampaignSummary;
import com.example.fausto_importados_api.model.Campaign;
import com.example.fausto_importados_api.model.CampaignCollection;
import com.example.fausto_importados_api.model.CampaignProduct;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/** Chamar dentro da transação, ou com as coleções já carregadas. */
@Component
public class CampaignMapper {

    public CampaignDetail detail(Campaign c, Instant now) {
        return new CampaignDetail(c.getId(), c.getName(), c.getDescription(), c.getType(), c.getPriority(),
                c.getStartsAt(), c.getEndsAt(), c.getTheme(), c.getContent(), collections(c), c.getStatus(),
                c.phaseAt(now), c.getVersion(), c.getCreatedAt(), c.getUpdatedAt(), c.getCreatedBy(),
                c.getUpdatedBy(), c.getPublishedAt());
    }

    public CampaignSummary summary(Campaign c, Instant now) {
        return new CampaignSummary(c.getId(), c.getName(), c.getType(), c.getStatus(), c.phaseAt(now),
                c.getPriority(), c.getStartsAt(), c.getEndsAt(), c.getUpdatedAt(), c.getUpdatedBy(),
                c.getCollections().size(), c.getVersion());
    }

    // ordenado aqui: depois de uma alteração na mesma transação, a ordem em memória não é a das posições
    private List<CampaignDetail.CollectionDetail> collections(Campaign campaign) {
        return campaign.getCollections().stream()
                .sorted(Comparator.comparingInt(CampaignCollection::getPosition))
                .map(collection -> new CampaignDetail.CollectionDetail(
                        collection.getId(), collection.getTitle(), collection.getSlug(), collection.getPosition(),
                        collection.getProducts().stream()
                                .sorted(Comparator.comparingInt(CampaignProduct::position))
                                .map(p -> new CampaignDetail.ProductEntry(p.productId(), p.position(), null))
                                .toList()))
                .toList();
    }
}
