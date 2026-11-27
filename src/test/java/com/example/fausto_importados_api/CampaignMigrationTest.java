package com.example.fausto_importados_api;

import com.example.fausto_importados_api.support.TestPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A V10 (campanhas) sobre uma base que já existe: o banco é levado até a V9, recebe dados e só então é migrado.
 * Depois confere as constraints das tabelas novas. Usa um schema próprio, apagado no fim.
 *
 * <p>De onde vem o banco: ver {@link TestPostgres}. Sem Docker e sem banco externo, é pulado.
 */
class CampaignMigrationTest {

    private static final String SCHEMA = "campaigns_" + UUID.randomUUID().toString().replace("-", "");
    private static final UUID PRODUCT = UUID.randomUUID();

    private static SingleConnectionDataSource dataSource;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate tx;
    private static Map<String, Object> productBefore;

    @BeforeAll
    static void migrateAnExistingDatabase() {
        TestPostgres.startOrSkip();

        flyway("9").migrate();

        dataSource = new SingleConnectionDataSource();
        dataSource.setUrl(TestPostgres.url());
        dataSource.setUsername(TestPostgres.username());
        dataSource.setPassword(TestPostgres.password());
        Properties properties = new Properties();
        properties.setProperty("currentSchema", SCHEMA);
        dataSource.setConnectionProperties(properties);
        dataSource.setSuppressClose(true);
        jdbc = new JdbcTemplate(dataSource);
        tx = new TransactionTemplate(new JdbcTransactionManager(dataSource));

        insertProduct(PRODUCT, "Perfume que ja existia");
        productBefore = product(PRODUCT);

        flyway("10").migrate();
    }

    @AfterAll
    static void dropSchema() {
        if (jdbc != null) {
            jdbc.execute("drop schema " + SCHEMA + " cascade");
            dataSource.destroy();
        }
    }

    @Test
    void theMigrationAppliesOverExistingDataWithoutChangingIt() {
        List<String> versions = jdbc.queryForList(
                "select version from flyway_schema_history where success and version is not null order by installed_rank",
                String.class);

        assertThat(versions).endsWith("9", "10");
        assertThat(product(PRODUCT)).isEqualTo(productBefore);
    }

    @Test
    void aDraftMayBeIncomplete() {
        UUID id = insertDraft();

        Map<String, Object> row = jdbc.queryForMap("select * from campaigns where id = ?", id);

        assertThat(row).containsEntry("status", "DRAFT").containsEntry("priority", 0).containsEntry("version", 0L);
        assertThat(row.get("name")).isNull();
        assertThat(row.get("starts_at")).isNull();
    }

    @Test
    void aPublishedCampaignRequiresNameTypePeriodAndTheme() {
        UUID id = insertCompleteDraft();
        update(id, "status = 'PUBLISHED'");

        for (String column : List.of("name", "type", "starts_at", "ends_at", "theme")) {
            assertRejected("chk_campaigns_published", () -> update(id, column + " = null"));
        }
    }

    @Test
    void anIncompleteCampaignCanBeArchivedOrDisabled() {
        UUID id = insertDraft();

        update(id, "status = 'ARCHIVED'");
        update(id, "status = 'DISABLED'");
    }

    @Test
    void theEndMustComeAfterTheStart() {
        UUID id = insertCompleteDraft();

        assertRejected("chk_campaigns_period", () -> update(id, "ends_at = starts_at"));
        assertRejected("chk_campaigns_period", () -> update(id, "ends_at = starts_at - interval '1 second'"));
    }

    @Test
    void onlyKnownStatusesTypesAndPrioritiesAreAccepted() {
        UUID id = insertDraft();

        for (String type : List.of("BLACK_FRIDAY", "CHRISTMAS", "EASTER", "VALENTINES_DAY", "MOTHERS_DAY",
                "FATHERS_DAY", "CONSUMER_DAY", "STORE_ANNIVERSARY", "CUSTOM")) {
            update(id, "type = '" + type + "'");
        }
        update(id, "priority = 100");

        assertRejected("chk_campaigns_type", () -> update(id, "type = 'CARNIVAL'"));
        // agendada, ativa e expirada são calculadas, nunca gravadas
        assertRejected("chk_campaigns_status", () -> update(id, "status = 'ACTIVE'"));
        assertRejected("chk_campaigns_priority", () -> update(id, "priority = -1"));
        assertRejected("chk_campaigns_priority", () -> update(id, "priority = 101"));
    }

    @Test
    void theSlugIsUniqueInsideTheCampaignOnly() {
        UUID campaign = insertDraft();
        insertCollection(campaign, "ofertas", 0);

        insertCollection(insertDraft(), "ofertas", 0);
        assertRejected("uq_campaign_collections_slug", () -> insertCollection(campaign, "ofertas", 1));
    }

    @Test
    void aSlugCanMoveToAnotherCollectionInsideOneTransaction() {
        UUID campaign = insertDraft();
        UUID old = insertCollection(campaign, "ofertas", 0);

        tx.executeWithoutResult(status -> {
            insertCollection(campaign, "ofertas", 0);
            jdbc.update("delete from campaign_collections where id = ?", old);
        });

        assertThat(count("campaign_collections where campaign_id = ?", campaign)).isEqualTo(1);
    }

    @Test
    void aProductMustExistAndAppearsOncePerCollection() {
        UUID campaign = insertDraft();
        UUID collection = insertCollection(campaign, "ofertas", 0);
        link(collection, PRODUCT);

        link(insertCollection(campaign, "mais-vendidos", 1), PRODUCT);
        assertRejected("pk_campaign_collection_products", () -> link(collection, PRODUCT));
        assertRejected("fk_campaign_collection_products_product", () -> link(collection, UUID.randomUUID()));
    }

    @Test
    void aProductInACampaignCannotBeDeleted_andDeletingTheCampaignKeepsTheProduct() {
        UUID product = UUID.randomUUID();
        insertProduct(product, "Perfume em campanha");
        Map<String, Object> before = product(product);
        UUID campaign = insertDraft();
        UUID collection = insertCollection(campaign, "ofertas", 0);
        link(collection, product);

        assertRejected("fk_campaign_collection_products_product",
                () -> jdbc.update("delete from products where id = ?", product));

        jdbc.update("delete from campaigns where id = ?", campaign);

        assertThat(count("campaign_collections where campaign_id = ?", campaign)).isZero();
        assertThat(count("campaign_collection_products where collection_id = ?", collection)).isZero();
        assertThat(product(product)).isEqualTo(before);
    }

    // ───────────────────────────── helpers ─────────────────────────────

    private static Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(TestPostgres.url(), TestPostgres.username(), TestPostgres.password())
                .schemas(SCHEMA)
                .defaultSchema(SCHEMA)
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }

    private static void assertRejected(String constraint, Runnable write) {
        assertThatThrownBy(write::run)
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(constraint);
    }

    private static void insertProduct(UUID id, String name) {
        jdbc.update("""
                insert into products (id, name, brand, description, olfactive_family, category, size, price,
                                      original_price, image, in_stock, stock_quantity)
                values (?, ?, 'Marca', 'Descricao', 'floral', 'UNISSEX', '100ml', 199.90, 249.90,
                        'https://example.com/p.webp', true, 3)
                """, id, name);
    }

    private static Map<String, Object> product(UUID id) {
        return jdbc.queryForMap("select * from products where id = ?", id);
    }

    private UUID insertDraft() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into campaigns (id, created_at, updated_at, created_by, updated_by)
                values (?, now(), now(), 'admin@test.com', 'admin@test.com')
                """, id);
        return id;
    }

    private UUID insertCompleteDraft() {
        UUID id = insertDraft();
        update(id, """
                name = 'Black Friday', type = 'BLACK_FRIDAY', starts_at = '2026-11-27T03:00:00Z',
                ends_at = '2026-12-01T03:00:00Z', theme = '{"dark": {}, "light": null}'
                """);
        return id;
    }

    /** {@code assignments} são constantes escritas neste teste. */
    private void update(UUID campaign, String assignments) {
        jdbc.update("update campaigns set " + assignments + " where id = ?", campaign);
    }

    private UUID insertCollection(UUID campaign, String slug, int position) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into campaign_collections (id, campaign_id, title, slug, position) values (?, ?, ?, ?, ?)",
                id, campaign, "Ofertas", slug, position);
        return id;
    }

    private void link(UUID collection, UUID product) {
        jdbc.update("insert into campaign_collection_products (collection_id, product_id, position) values (?, ?, 0)",
                collection, product);
    }

    private int count(String tableAndFilter, UUID id) {
        return jdbc.queryForObject("select count(*) from " + tableAndFilter, Integer.class, id);
    }
}
