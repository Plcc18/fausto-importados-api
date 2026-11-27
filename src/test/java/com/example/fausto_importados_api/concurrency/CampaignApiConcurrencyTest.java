package com.example.fausto_importados_api.concurrency;

import com.example.fausto_importados_api.model.Campaign;
import com.example.fausto_importados_api.model.Product;
import com.example.fausto_importados_api.model.enums.CampaignStatus;
import com.example.fausto_importados_api.repository.CampaignRepository;
import com.example.fausto_importados_api.support.CampaignFixtures;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** As rotas de campanhas contra a API e o PostgreSQL de verdade. */
class CampaignApiConcurrencyTest extends ConcurrencyTestSupport {

    private static final String BASE = "/api/admin/campaigns";
    private static final int THREADS = 6;

    @Autowired CampaignRepository campaigns;
    @Autowired com.example.fausto_importados_api.services.ActiveCampaignCache storeCache;

    @BeforeEach
    @AfterEach
    void cleanUp() {
        jdbc.update("delete from campaigns");
        storeCache.invalidate();
    }

    @Test
    void onlyOneOfManySimultaneousEditsOfTheSameVersionWins() throws Exception {
        Session admin = admin();
        UUID id = save(CampaignStatus.DRAFT, 0, day(1), day(5));

        List<Res> results;
        try (AutoCloseable slow = slowDown("campaigns", "update", 0.2)) {
            results = concurrently(THREADS, i -> () ->
                    call("PUT", BASE + "/" + id, admin, Map.of("name", "Edição " + i, "version", 0)));
        }

        assertThat(countStatus(results, 200)).isEqualTo(1);
        assertThat(results.stream().filter(r -> r.status() != 200))
                .hasSize(THREADS - 1)
                .allSatisfy(r -> {
                    assertThat(r.status()).isEqualTo(409);
                    assertThat(r.body().path("code").asText()).isEqualTo("version_conflict");
                });
        assertThat(campaigns.findById(id).orElseThrow().getVersion()).isEqualTo(1);
    }

    @Test
    void simultaneousCreationsWithTheSameKeyCreateOnce() {
        Session admin = admin();
        String key = UUID.randomUUID().toString();

        List<Res> results = concurrently(THREADS, i -> () ->
                call("POST", BASE, admin, Map.of("name", "Natal"), Map.of("Idempotency-Key", key)));

        assertThat(statuses(results)).containsOnly(201);
        assertThat(results.stream().map(r -> r.body().path("id").asText()).distinct()).hasSize(1);
        assertThat(campaigns.count()).isEqualTo(1);
    }

    @Test
    void twoPublishedCampaignsCannotBeEditedIntoTheSamePeriod() throws Exception {
        Session admin = admin();
        UUID first = save(CampaignStatus.PUBLISHED, 5, day(1), day(5));
        UUID second = save(CampaignStatus.PUBLISHED, 5, day(20), day(25));
        List<UUID> ids = List.of(first, second);
        List<Map<String, Object>> changes = new ArrayList<>();
        for (UUID id : ids) {
            Map<String, Object> change = document(admin, id);
            change.put("startsAt", day(10).toString()); // as duas tentam ocupar o mesmo período
            change.put("endsAt", day(15).toString());
            changes.add(change);
        }

        List<Res> results;
        try (AutoCloseable slow = slowDown("campaigns", "update", 0.2)) {
            results = concurrently(2, i -> () -> call("PUT", BASE + "/" + ids.get(i), admin, changes.get(i)));
        }

        assertThat(statuses(results)).containsExactlyInAnyOrder(200, 409);
        assertThat(results.stream().filter(r -> r.status() == 409))
                .allSatisfy(r -> assertThat(r.body().path("code").asText()).isEqualTo("campaign_conflict"));
    }

    /** A ordenação, os filtros e a busca no PostgreSQL, que trata nulos e LIKE à sua maneira. */
    @Test
    void theListBehavesTheSameOnTheRealDatabase() {
        Session admin = admin();
        UUID older = save(CampaignStatus.PUBLISHED, 1, day(1), day(5));
        UUID newer = save(CampaignStatus.PUBLISHED, 2, day(10), day(15));
        UUID undated = save(CampaignStatus.DRAFT, 0, null, null);

        Res all = call("GET", BASE, admin, null);
        Res filtered = call("GET", BASE + "?status=PUBLISHED,DRAFT&phase=SCHEDULED&type=BLACK_FRIDAY&q=black"
                + "&from=" + day(11) + "&to=" + day(30), admin, null);
        Res wildcard = call("GET", BASE + "?q=%25", admin, null);

        assertThat(ids(all)).containsExactly(undated.toString(), newer.toString(), older.toString());
        assertThat(filtered.status()).isEqualTo(200);
        assertThat(ids(filtered)).containsExactly(newer.toString());
        assertThat(ids(wildcard)).isEmpty();
    }

    @Test
    void onlyOneOfManySimultaneousPublicationsOfTheSamePeriodWins() throws Exception {
        Session admin = admin();
        List<UUID> drafts = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            drafts.add(save(CampaignStatus.DRAFT, 5, day(10), day(15)));
        }

        List<Res> results;
        try (AutoCloseable slow = slowDown("campaigns", "update", 0.2)) {
            results = concurrently(THREADS, i -> () ->
                    call("POST", BASE + "/" + drafts.get(i) + "/publish", admin, Map.of("version", 0)));
        }

        assertThat(countStatus(results, 200)).isEqualTo(1);
        assertThat(results.stream().filter(r -> r.status() != 200))
                .hasSize(THREADS - 1)
                .allSatisfy(r -> {
                    assertThat(r.status()).isEqualTo(409);
                    assertThat(r.body().path("code").asText()).isEqualTo("campaign_conflict");
                });
    }

    @Test
    void publishingAndDeletingTheSameDraftAtOnceCannotBothHappen() throws Exception {
        Session admin = admin();
        UUID id = save(CampaignStatus.DRAFT, 5, day(10), day(15));

        List<Res> results;
        try (AutoCloseable slow = slowDown("campaigns", "update or delete", 0.2)) {
            results = concurrently(2, i -> () -> i == 0
                    ? call("POST", BASE + "/" + id + "/publish", admin, Map.of("version", 0))
                    : call("DELETE", BASE + "/" + id + "?version=0", admin, null));
        }

        Res publish = results.get(0);
        Res delete = results.get(1);
        if (publish.status() == 200) {
            assertThat(delete.status()).isEqualTo(409); // a versão mudou
            assertThat(campaigns.findById(id).orElseThrow().getStatus()).isEqualTo(CampaignStatus.PUBLISHED);
        } else {
            assertThat(delete.status()).isEqualTo(204);
            assertThat(publish.status()).isEqualTo(404);
            assertThat(campaigns.findById(id)).isEmpty();
        }
    }

    @Test
    void oneEditCanSwapAndReuseSlugsWithoutTouchingTheProducts() {
        Session admin = admin();
        Product a = newProduct(1);
        Product b = newProduct(1);
        List<Map<String, Object>> before = productRows();
        Campaign campaign = CampaignFixtures.complete();
        campaign.addCollection(CampaignFixtures.collection("ofertas", 0, a.getId(), b.getId()));
        campaign.addCollection(CampaignFixtures.collection("presentes", 1, a.getId()));
        campaign.addCollection(CampaignFixtures.collection("sobra", 2));
        UUID id = campaigns.saveAndFlush(campaign).getId();
        Map<String, Object> document = document(admin, id);
        JsonNode current = json.valueToTree(document.get("collections"));

        document.put("collections", List.of(
                collection(current.path(1).path("id").asText(), "ofertas", a.getId()),
                collection(null, "sobra", b.getId()),
                collection(current.path(0).path("id").asText(), "presentes", b.getId(), a.getId())));
        Res res = call("PUT", BASE + "/" + id, admin, document);

        assertThat(res.status()).isEqualTo(200);
        assertThat(res.body().path("collections").findValuesAsText("slug"))
                .containsExactly("ofertas", "sobra", "presentes");
        assertThat(count("campaign_collections")).isEqualTo(3);
        assertThat(count("campaign_collection_products")).isEqualTo(4);
        assertThat(productRows()).isEqualTo(before);
    }

    /** A leitura pública usa uma transação REPEATABLE READ somente leitura: confere no banco de verdade. */
    @Test
    void theStoreReadsTheActiveCampaignFromTheRealDatabase() {
        Product active = newProduct(1);
        Product inactive = newProduct(1);
        inactive.setActive(false);
        products.save(inactive);
        Campaign campaign = CampaignFixtures.complete();
        campaign.setStatus(CampaignStatus.PUBLISHED);
        campaign.setStartsAt(day(-1));
        campaign.setEndsAt(day(5));
        campaign.addCollection(CampaignFixtures.collection("ofertas", 0, inactive.getId(), active.getId()));
        UUID id = campaigns.saveAndFlush(campaign).getId();

        Res res = call("GET", "/api/campaigns/active", null, null, Map.of("X-Forwarded-For", freshIp()));

        assertThat(res.status()).isEqualTo(200);
        assertThat(res.body().path("campaign").path("id").asText()).isEqualTo(id.toString());
        assertThat(res.body().path("campaign").path("collections").path(0).path("productIds"))
                .singleElement()
                .satisfies(productId -> assertThat(productId.asText()).isEqualTo(active.getId().toString()));
        assertThat(res.body().path("nextChangeAt").asText()).isEqualTo(campaign.getEndsAt().toString());
    }

    @Test
    void theHistoryIsRecordedAndReadOnTheRealDatabase() throws Exception {
        Session admin = admin();
        Product product = newProduct(1);
        Campaign campaign = CampaignFixtures.complete();
        campaign.setStartsAt(day(10));
        campaign.setEndsAt(day(15));
        campaign.addCollection(CampaignFixtures.collection("ofertas", 0, product.getId()));
        UUID id = campaigns.saveAndFlush(campaign).getId();

        assertThat(call("POST", BASE + "/" + id + "/publish", admin, Map.of("version", 0)).status()).isEqualTo(200);
        assertThat(call("POST", BASE + "/" + id + "/disable", admin, Map.of("version", 1)).status()).isEqualTo(200);
        Res history = call("GET", BASE + "/" + id + "/history", admin, null);

        assertThat(history.status()).isEqualTo(200);
        assertThat(history.body().path("content").findValuesAsText("action"))
                .containsExactly("CAMPAIGN_DISABLED", "CAMPAIGN_PUBLISHED");
        JsonNode details = json.readTree(history.body().path("content").path(1).path("details").asText());
        assertThat(details.path("changes").path("status").path("to").asText()).isEqualTo("PUBLISHED");
        assertThat(details.path("changes").has("collections")).isFalse(); // publicar não as altera
    }

    @Test
    void duplicatingAndDeletingWorkWithCollectionsOnTheRealDatabase() {
        Session admin = admin();
        Product a = newProduct(1);
        Product b = newProduct(1);
        Campaign original = CampaignFixtures.complete();
        original.addCollection(CampaignFixtures.collection("ofertas", 0, a.getId(), b.getId()));
        original.addCollection(CampaignFixtures.collection("presentes", 1, a.getId()));
        UUID id = campaigns.saveAndFlush(original).getId();

        Res copy = call("POST", BASE + "/" + id + "/duplicate", admin, null,
                Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        assertThat(copy.status()).isEqualTo(201);
        String copyId = copy.body().path("id").asText();
        assertThat(count("campaign_collections")).isEqualTo(4);

        Res deleted = call("DELETE", BASE + "/" + copyId + "?version=0", admin, null);

        assertThat(deleted.status()).isEqualTo(204);
        assertThat(count("campaign_collections")).isEqualTo(2);
        assertThat(count("campaign_collection_products")).isEqualTo(3);
        assertThat(stockOf(a)).isEqualTo(1);
    }

    // ───────────────────────────── helpers ─────────────────────────────

    private UUID save(CampaignStatus status, int priority, Instant start, Instant end) {
        Campaign campaign = CampaignFixtures.complete();
        campaign.setStatus(status);
        campaign.setPriority(priority);
        campaign.setStartsAt(start);
        campaign.setEndsAt(end);
        return campaigns.saveAndFlush(campaign).getId();
    }

    private Map<String, Object> document(Session admin, UUID id) {
        JsonNode body = call("GET", BASE + "/" + id, admin, null).body();
        @SuppressWarnings("unchecked")
        Map<String, Object> document = json.convertValue(body, LinkedHashMap.class);
        return document;
    }

    private static Map<String, Object> collection(String id, String slug, UUID... products) {
        Map<String, Object> collection = new LinkedHashMap<>();
        collection.put("id", id);
        collection.put("title", "Coleção");
        collection.put("slug", slug);
        collection.put("products", Arrays.stream(products).map(p -> Map.of("productId", p)).toList());
        return collection;
    }

    private List<Map<String, Object>> productRows() {
        return jdbc.queryForList("select * from products order by id");
    }

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    private static List<String> ids(Res res) {
        List<String> ids = new ArrayList<>();
        res.body().path("content").forEach(item -> ids.add(item.path("id").asText()));
        return ids;
    }

    /** Sempre no futuro: a API usa o relógio real. */
    private static Instant day(int day) {
        return Instant.now().truncatedTo(ChronoUnit.DAYS).plus(day, ChronoUnit.DAYS);
    }
}
