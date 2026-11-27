package com.example.fausto_importados_api.model;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.BatchSize;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Seção de produtos de uma campanha (ex.: "Ofertas Black Friday"). */
@Entity
@Table(name = "campaign_collections")
@Getter
@Setter
@NoArgsConstructor
public class CampaignCollection {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "campaign_id", nullable = false, updatable = false)
    private Campaign campaign;

    @Column(nullable = false, length = 60)
    private String title;

    /** Identificador do link {@code /?colecao=<slug>}; único dentro da campanha (conferido pelo banco no commit). */
    @Column(nullable = false, length = 40)
    private String slug;

    @Column(nullable = false)
    private int position;

    /** Em ordem de {@code position} quando lidos do banco. Troque a lista com {@link #replaceProducts}. */
    @Setter(AccessLevel.NONE)
    @ElementCollection
    @CollectionTable(name = "campaign_collection_products", joinColumns = @JoinColumn(name = "collection_id"))
    @OrderBy("position ASC")
    @BatchSize(size = 20) // uma consulta só para os produtos de todas as coleções de uma campanha
    private Set<CampaignProduct> products = new LinkedHashSet<>();

    /**
     * @param productIds na ordem de exibição, sem repetições; as posições são numeradas de 0 em diante
     */
    public void replaceProducts(List<UUID> productIds) {
        if (productIds.stream().distinct().count() != productIds.size()) {
            throw new IllegalArgumentException("A collection cannot list the same product twice");
        }
        products.clear();
        for (int index = 0; index < productIds.size(); index++) {
            products.add(new CampaignProduct(productIds.get(index), index));
        }
    }

    /** Coleção nova, ainda sem campanha, com o mesmo conteúdo. */
    CampaignCollection copy() {
        CampaignCollection copy = new CampaignCollection();
        copy.title = title;
        copy.slug = slug;
        copy.position = position;
        copy.replaceProducts(products.stream()
                .sorted(Comparator.comparingInt(CampaignProduct::position))
                .map(CampaignProduct::productId)
                .toList());
        return copy;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CampaignCollection collection)) return false;
        return id != null && id.equals(collection.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
