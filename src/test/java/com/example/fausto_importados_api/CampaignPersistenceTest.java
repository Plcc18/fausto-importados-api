package com.example.fausto_importados_api;

import com.example.fausto_importados_api.model.Campaign;
import com.example.fausto_importados_api.model.Product;
import com.example.fausto_importados_api.model.enums.CampaignStatus;
import com.example.fausto_importados_api.model.enums.Category;
import com.example.fausto_importados_api.repository.CampaignRepository;
import com.example.fausto_importados_api.repository.ProductRepository;
import com.example.fausto_importados_api.support.TestPostgres;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static com.example.fausto_importados_api.support.CampaignFixtures.AUTHOR;
import static com.example.fausto_importados_api.support.CampaignFixtures.NOW;
import static com.example.fausto_importados_api.support.CampaignFixtures.bySlug;
import static com.example.fausto_importados_api.support.CampaignFixtures.collection;
import static com.example.fausto_importados_api.support.CampaignFixtures.complete;
import static com.example.fausto_importados_api.support.CampaignFixtures.draft;
import static com.example.fausto_importados_api.support.CampaignFixtures.positions;
import static com.example.fausto_importados_api.support.CampaignFixtures.productIds;
import static com.example.fausto_importados_api.support.CampaignFixtures.slugs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Entidades das campanhas sobre o schema criado pelo Flyway num PostgreSQL de verdade (o contexto só sobe se o
 * {@code validate} do Hibernate aceitar o schema): JSONB, constraints e a ordem em que as escritas chegam ao banco.
 *
 * <p>De onde vem o banco: ver {@link TestPostgres}. Sem Docker e sem banco externo, é pulado.
 */
@SpringBootTest
@ActiveProfiles("test")
class CampaignPersistenceTest {

    @BeforeAll
    static void startDatabase() {
        TestPostgres.startOrSkip();
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestPostgres.register(registry);
    }

    @Autowired CampaignRepository campaigns;
    @Autowired ProductRepository products;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;

    private final List<UUID> createdCampaigns = new ArrayList<>();
    private final List<UUID> createdProducts = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        createdCampaigns.forEach(id -> jdbc.update("delete from campaigns where id = ?", id));
        products.deleteAllById(createdProducts);
    }

    @Test
    void themeAndContentAreStoredAsJsonbAndReadBack() {
        Campaign original = complete();
        UUID id = save(original);

        Campaign read = campaigns.findDetailById(id).orElseThrow();

        assertThat(read.getTheme()).isEqualTo(original.getTheme());
        assertThat(read.getContent()).isEqualTo(original.getContent());
        assertThat(read.getStartsAt()).isEqualTo(original.getStartsAt());
        assertThat(jdbc.queryForObject("select theme -> 'dark' ->> 'primary' from campaigns where id = ?",
                String.class, id)).isEqualTo("#C1121F");
        // campo opcional sem valor fica no JSON como null, não é omitido
        assertThat(jdbc.queryForObject("select jsonb_typeof(theme -> 'light') from campaigns where id = ?",
                String.class, id)).isEqualTo("null");
    }

    @Test
    void aMissingThemeIsSqlNull_soItCannotBePublished() {
        Campaign campaign = complete();
        campaign.setTheme(null);
        UUID id = save(campaign);

        assertThat(jdbc.queryForObject("select theme is null from campaigns where id = ?", Boolean.class, id)).isTrue();
        assertThatThrownBy(() -> edit(id, c -> c.setStatus(CampaignStatus.PUBLISHED)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_campaigns_published");
    }

    @Test
    void anEmptyDraftIsAccepted() {
        UUID id = save(draft());

        assertThat(campaigns.findDetailById(id)).isPresent();
    }

    @Test
    void readingWithoutChangingKeepsTheVersion() {
        UUID id = save(complete());

        edit(id, campaign -> assertThat(campaign.getContent()).isNotNull());

        assertThat(campaigns.findById(id).orElseThrow().getVersion()).isZero();
    }

    @Test
    void oneEditCanReorderProductsAndReuseOrSwapSlugs() {
        UUID a = product();
        UUID b = product();
        UUID c = product();
        Campaign campaign = complete();
        campaign.addCollection(collection("ofertas", 0, a, b, c));
        campaign.addCollection(collection("mais-vendidos", 1, a));
        campaign.addCollection(collection("presentes", 2));
        UUID id = save(campaign);

        edit(id, editing -> {
            bySlug(editing, "ofertas").replaceProducts(List.of(c, a)); // troca a ordem e remove um
            var first = bySlug(editing, "ofertas");
            var second = bySlug(editing, "mais-vendidos");
            first.setSlug("mais-vendidos");
            second.setSlug("ofertas");
            editing.getCollections().remove(bySlug(editing, "presentes"));
            editing.addCollection(collection("presentes", 2, b)); // coleção nova com o identificador da removida
            editing.registerChange(NOW.plusSeconds(60), AUTHOR);
        });

        Campaign read = campaigns.findDetailById(id).orElseThrow();
        assertThat(slugs(read)).containsExactly("mais-vendidos", "ofertas", "presentes");
        assertThat(productIds(read, "mais-vendidos")).containsExactly(c, a);
        assertThat(positions(read, "mais-vendidos")).containsExactly(0, 1);
        assertThat(productIds(read, "presentes")).containsExactly(b);
        assertThat(read.getVersion()).isEqualTo(1);
    }

    @Test
    void aRepeatedSlugIsRefusedWhenTheEditIsCommitted() {
        Campaign campaign = complete();
        campaign.addCollection(collection("ofertas", 0));
        UUID id = save(campaign);

        assertThatThrownBy(() -> edit(id, editing -> editing.addCollection(collection("ofertas", 1))))
                .hasStackTraceContaining("uq_campaign_collections_slug");
    }

    @Test
    void deletingTheCampaignRemovesItsChildrenAndKeepsTheProducts() {
        UUID a = product();
        Campaign campaign = complete();
        campaign.addCollection(collection("ofertas", 0, a));
        UUID id = save(campaign);
        UUID collectionId = jdbc.queryForObject(
                "select id from campaign_collections where campaign_id = ?", UUID.class, id);

        tx.executeWithoutResult(status -> campaigns.delete(campaigns.findByIdForUpdate(id).orElseThrow()));

        assertThat(campaigns.findById(id)).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from campaign_collection_products where collection_id = ?",
                Integer.class, collectionId)).isZero();
        assertThat(products.findById(a)).hasValueSatisfying(p -> assertThat(p.getVersion()).isZero());
    }

    // ───────────────────────────── helpers ─────────────────────────────

    private UUID save(Campaign campaign) {
        UUID id = campaigns.saveAndFlush(campaign).getId();
        createdCampaigns.add(id);
        return id;
    }

    /** Como um serviço faz: trava a campanha, altera e confirma. */
    private void edit(UUID id, Consumer<Campaign> change) {
        tx.executeWithoutResult(status -> change.accept(campaigns.findByIdForUpdate(id).orElseThrow()));
    }

    private UUID product() {
        Product p = new Product();
        p.setName("Perfume " + UUID.randomUUID());
        p.setBrand("Marca");
        p.setDescription("Descrição");
        p.setCategory(Category.UNISSEX);
        p.setOlfactiveFamily("FLORAL");
        p.setSize("100ml");
        p.setPrice(new BigDecimal("199.90"));
        p.setImage("https://example.com/p.webp");
        p.setInStock(true);
        p.setStockQuantity(1);
        UUID id = products.saveAndFlush(p).getId();
        createdProducts.add(id);
        return id;
    }
}
