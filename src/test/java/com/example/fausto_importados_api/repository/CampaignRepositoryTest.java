package com.example.fausto_importados_api.repository;

import com.example.fausto_importados_api.model.Campaign;
import com.example.fausto_importados_api.model.CampaignCollection;
import com.example.fausto_importados_api.model.enums.CampaignStatus;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static com.example.fausto_importados_api.support.CampaignFixtures.AUTHOR;
import static com.example.fausto_importados_api.support.CampaignFixtures.NOW;
import static com.example.fausto_importados_api.support.CampaignFixtures.bySlug;
import static com.example.fausto_importados_api.support.CampaignFixtures.collection;
import static com.example.fausto_importados_api.support.CampaignFixtures.complete;
import static com.example.fausto_importados_api.support.CampaignFixtures.draft;
import static com.example.fausto_importados_api.support.CampaignFixtures.productIds;
import static com.example.fausto_importados_api.support.CampaignFixtures.slugs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Mapeamento e consultas das campanhas em H2. O que depende do PostgreSQL está em CampaignPersistenceTest. */
@DataJpaTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@ActiveProfiles("test")
class CampaignRepositoryTest {

    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();
    private static final UUID C = UUID.randomUUID();

    @Autowired CampaignRepository campaigns;
    @Autowired TestEntityManager em;
    @Autowired EntityManagerFactory emf;

    @Test
    void aDraftStartsEmptyWithTheDefaults() {
        UUID id = save(draft());

        Campaign read = campaigns.findDetailById(id).orElseThrow();

        assertThat(read.getStatus()).isEqualTo(CampaignStatus.DRAFT);
        assertThat(read.getPriority()).isZero();
        assertThat(read.getVersion()).isZero();
        assertThat(read.getCreatedAt()).isEqualTo(NOW);
        assertThat(read.getCreatedBy()).isEqualTo(AUTHOR);
        assertThat(read.getName()).isNull();
        assertThat(read.getTheme()).isNull();
        assertThat(read.getContent()).isNull();
        assertThat(read.getCollections()).isEmpty();
    }

    @Test
    void everyFieldSurvivesTheRoundTrip() {
        Campaign original = complete();
        UUID id = save(original);

        Campaign read = campaigns.findDetailById(id).orElseThrow();

        assertThat(read).usingRecursiveComparison().ignoringFields("collections").isEqualTo(original);
    }

    @Test
    void collectionsAndProductsComeBackInTheirOrder() {
        Campaign campaign = complete();
        campaign.addCollection(collection("terceira", 2, A));
        campaign.addCollection(collection("primeira", 0, C, A, B));
        campaign.addCollection(collection("segunda", 1));
        UUID id = save(campaign);

        Campaign read = campaigns.findDetailById(id).orElseThrow();

        assertThat(slugs(read)).containsExactly("primeira", "segunda", "terceira");
        assertThat(productIds(read, "primeira")).containsExactly(C, A, B);
        assertThat(productIds(read, "segunda")).isEmpty();
    }

    @Test
    void theDetailIsLoadedInOneQuery_andCanBeReadAfterTheSessionEnds() {
        Campaign campaign = complete();
        campaign.addCollection(collection("ofertas", 0, A, B));
        campaign.addCollection(collection("mais-vendidos", 1, B, C));
        UUID id = save(campaign);
        Statistics statistics = freshStatistics();

        Campaign read = campaigns.findDetailById(id).orElseThrow();
        em.clear(); // desanexa: uma coleção não carregada lançaria LazyInitializationException

        assertThat(read.getCollections()).hasSize(2);
        assertThat(productIds(read, "mais-vendidos")).containsExactly(B, C);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
    }

    @Test
    void walkingManyCampaignsDoesNotQueryOncePerCampaignOrCollection() {
        for (int i = 0; i < 3; i++) {
            Campaign campaign = complete();
            campaign.addCollection(collection("ofertas", 0, A));
            campaign.addCollection(collection("mais-vendidos", 1, B));
            campaigns.save(campaign);
        }
        em.flush();
        em.clear();
        Statistics statistics = freshStatistics();

        long products = campaigns.findAll().stream()
                .flatMap(c -> c.getCollections().stream())
                .mapToLong(c -> c.getProducts().size())
                .sum();

        assertThat(products).isEqualTo(6);
        // campanhas, coleções e produtos: uma consulta cada
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(3);
    }

    @Test
    void theLockedLoadReachesTheProductsWithoutOneQueryPerCollection() {
        Campaign campaign = complete();
        for (int i = 0; i < 8; i++) {
            campaign.addCollection(collection("colecao-" + i, i, A, B));
        }
        UUID id = save(campaign);
        Statistics statistics = freshStatistics();

        Campaign locked = campaigns.findByIdForUpdate(id).orElseThrow();
        locked.getCollections().forEach(c -> assertThat(c.getProducts()).hasSize(2));

        assertThat(statistics.getPrepareStatementCount()).isEqualTo(3);
    }

    @Test
    void readingWithoutChangingKeepsTheVersion() {
        Campaign campaign = complete();
        campaign.addCollection(collection("ofertas", 0, A));
        UUID id = save(campaign);

        campaigns.findDetailById(id).orElseThrow();
        em.flush();
        em.clear();

        assertThat(campaigns.findById(id).orElseThrow().getVersion()).isZero();
    }

    @Test
    void aChangeOnlyInTheProductsRaisesTheVersionWhenItIsRegistered() {
        Campaign campaign = complete();
        campaign.addCollection(collection("ofertas", 0, A));
        UUID id = save(campaign);

        Campaign editing = campaigns.findDetailById(id).orElseThrow();
        bySlug(editing, "ofertas").replaceProducts(List.of(A, B));
        editing.registerChange(NOW.plusSeconds(60), "outra@test.com");
        em.flush();
        em.clear();

        Campaign read = campaigns.findDetailById(id).orElseThrow();
        assertThat(read.getVersion()).isEqualTo(1);
        assertThat(read.getCreatedAt()).isEqualTo(NOW);
        assertThat(read.getCreatedBy()).isEqualTo(AUTHOR);
        assertThat(read.getUpdatedBy()).isEqualTo("outra@test.com");
        assertThat(productIds(read, "ofertas")).containsExactly(A, B);
    }

    @Test
    void aRegisteredChangeRaisesTheVersionEvenIfTheClockHasNotMoved() {
        Campaign campaign = complete();
        campaign.addCollection(collection("ofertas", 0, A));
        UUID id = save(campaign);

        for (Instant sameOrEarlier : List.of(NOW, NOW.minusSeconds(60))) {
            Campaign editing = campaigns.findDetailById(id).orElseThrow();
            bySlug(editing, "ofertas").replaceProducts(List.of(B));
            editing.registerChange(sameOrEarlier, AUTHOR);
            em.flush();
            em.clear();
        }

        Campaign read = campaigns.findDetailById(id).orElseThrow();
        assertThat(read.getVersion()).isEqualTo(2);
        assertThat(read.getUpdatedAt()).isEqualTo(NOW.plus(2, ChronoUnit.MICROS));
        assertThat(read.getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    void aSaveOverAnOldVersionIsRefused() {
        UUID id = save(complete());
        Campaign stale = campaigns.findDetailById(id).orElseThrow();
        em.clear();

        Campaign fresh = campaigns.findDetailById(id).orElseThrow();
        fresh.setName("Nome novo");
        em.flush();
        em.clear();
        stale.setName("Nome antigo");

        assertThatThrownBy(() -> campaigns.saveAndFlush(stale))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    @Test
    void removingACollectionDeletesItAndItsProducts() {
        Campaign campaign = complete();
        campaign.addCollection(collection("ofertas", 0, A));
        campaign.addCollection(collection("mais-vendidos", 1, B));
        UUID id = save(campaign);

        Campaign editing = campaigns.findDetailById(id).orElseThrow();
        CampaignCollection removed = bySlug(editing, "ofertas");
        UUID removedId = removed.getId();
        editing.getCollections().remove(removed);
        em.flush();
        em.clear();

        assertThat(slugs(campaigns.findDetailById(id).orElseThrow())).containsExactly("mais-vendidos");
        assertThat(em.find(CampaignCollection.class, removedId)).isNull();
    }

    @Test
    void aCollectionRefusesTheSameProductTwice() {
        assertThatThrownBy(() -> collection("ofertas", 0, A, A)).isInstanceOf(IllegalArgumentException.class);
    }

    private UUID save(Campaign campaign) {
        UUID id = campaigns.save(campaign).getId();
        em.flush();
        em.clear();
        return id;
    }

    private Statistics freshStatistics() {
        Statistics statistics = emf.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        return statistics;
    }
}
