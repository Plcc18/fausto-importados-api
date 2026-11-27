package com.example.fausto_importados_api.controller;

import com.example.fausto_importados_api.audit.AuditAction;
import com.example.fausto_importados_api.audit.AuditService;
import com.example.fausto_importados_api.model.Product;
import com.example.fausto_importados_api.model.enums.Category;
import com.example.fausto_importados_api.model.enums.Role;
import com.example.fausto_importados_api.repository.ProductRepository;
import com.example.fausto_importados_api.support.CampaignFixtures;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** Quem fez o quê e quando em cada campanha, com o antes e depois. */
class CampaignHistoryApiTest extends CampaignApiTestSupport {

    @Autowired ProductRepository products;
    @Autowired AuditService audit;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping routes;

    @BeforeEach
    void clearTrail() {
        auditLog.deleteAll();
    }

    // ───────────────────────────── quem, o quê e quando ─────────────────────────────

    @Test
    void everyAdminOperationIsRecordedWithWhoWhatAndWhen() throws Exception {
        Set<String> done = new HashSet<>();
        Admin author = admin;
        String id = create(complete("Black Friday")).body().path("id").asText();
        done.add("POST /api/admin/campaigns");
        List<String> expected = new ArrayList<>(List.of("CAMPAIGN_CREATED"));

        Admin other = signIn(Role.ADMIN);
        admin = other;
        clock.advance(Duration.ofMinutes(1));
        assertThat(put(id, edit(id, "Black Friday 2026")).status()).isEqualTo(200);
        done.add("PUT /api/admin/campaigns/{id}");
        expected.add(0, "CAMPAIGN_UPDATED");

        Map<String, String> steps = new LinkedHashMap<>();
        steps.put("publish", "CAMPAIGN_PUBLISHED");
        steps.put("activate-now", "CAMPAIGN_ACTIVATED");
        steps.put("disable", "CAMPAIGN_DISABLED");
        steps.put("reactivate", "CAMPAIGN_REACTIVATED");
        for (Map.Entry<String, String> step : steps.entrySet()) {
            clock.advance(Duration.ofMinutes(1));
            action(id, step.getKey());
            done.add("POST /api/admin/campaigns/{id}/" + step.getKey());
            expected.add(0, step.getValue());
        }
        clock.advance(Duration.ofMinutes(1));
        action(id, "disable");
        expected.add(0, "CAMPAIGN_DISABLED");
        clock.advance(Duration.ofMinutes(1));
        action(id, "archive");
        done.add("POST /api/admin/campaigns/{id}/archive");
        expected.add(0, "CAMPAIGN_ARCHIVED");

        String copy = call("POST", BASE + "/" + id + "/duplicate", admin.token(), null,
                Map.of("Idempotency-Key", newKey())).body().path("id").asText();
        done.add("POST /api/admin/campaigns/{id}/duplicate");
        assertThat(call("DELETE", BASE + "/" + copy + "?version=0", admin.token(), null, Map.of()).status())
                .isEqualTo(204);
        done.add("DELETE /api/admin/campaigns/{id}");

        JsonNode entries = history(id).path("content");
        assertThat(texts(entries, "action")).isEqualTo(expected);
        assertThat(texts(history(copy).path("content"), "action"))
                .containsExactlyInAnyOrder("CAMPAIGN_DUPLICATED", "CAMPAIGN_DELETED");
        for (JsonNode entry : entries) {
            boolean creation = entry.path("action").asText().equals("CAMPAIGN_CREATED");
            assertThat(entry.path("actorEmail").asText()).isEqualTo((creation ? author : other).user().getEmail());
            assertThat(entry.path("actorId").asText()).isEqualTo((creation ? author : other).user().getId().toString());
            assertThat(entry.path("occurredAt").asText()).isNotBlank();
            assertThat(entry.path("entityType").asText()).isEqualTo("CAMPAIGN");
            assertThat(entry.path("entityId").asText()).isEqualTo(id);
            assertThat(entry.path("outcome").asText()).isEqualTo("SUCCESS");
            assertThat(entry.path("ip").asText()).isNotBlank();
            assertThat(details(entry).path("changes").isObject()).isTrue();
        }
        assertThat(entries.path(entries.size() - 1).path("occurredAt").asText()).isEqualTo(NOW.toString());
        // uma rota de escrita nova precisa entrar neste teste (e registrar a auditoria)
        assertThat(done).isEqualTo(writeRoutes());
    }

    @Test
    void whatIsRefusedOrRepeatedIsNotRecordedAgain() throws Exception {
        String key = newKey();
        String id = create(complete("Natal"), key).body().path("id").asText();
        assertThat(create(complete("Natal"), key).status()).isEqualTo(201); // repetição
        assertThat(put(id, Map.of("version", 9)).status()).isEqualTo(409);
        assertThat(put(id, Map.of("priority", 500, "version", 0)).status()).isEqualTo(400);
        assertThat(call("POST", BASE + "/" + id + "/disable", admin.token(), Map.of("version", 0), Map.of())
                .status()).isEqualTo(409);

        assertThat(texts(history(id).path("content"), "action")).containsExactly("CAMPAIGN_CREATED");
    }

    // ───────────────────────────── antes e depois ─────────────────────────────

    @Test
    void theCreationRecordsWhatWasFilledIn() throws Exception {
        String id = create(complete("Black Friday")).body().path("id").asText();

        JsonNode changes = changes(id);

        assertChange(changes, "name", null, "Black Friday");
        assertChange(changes, "status", null, "DRAFT");
        assertThat(changes.path("priority").path("to").asInt()).isEqualTo(10);
        assertChange(changes, "theme.dark.primary", null, "#C1121F");
        assertChange(changes, "content.hero.buttons[0].label", null, "Ver ofertas");
        assertThat(changes.has("description")).isFalse(); // vazio antes e depois
        assertThat(changes.has("theme.light")).isFalse();
    }

    @Test
    void anEditRecordsOnlyWhatChanged() throws Exception {
        String id = create(complete("Black Friday")).body().path("id").asText();
        Map<String, Object> change = edit(id, "Black Friday 2026");
        change.put("priority", 20);
        change.put("content", Map.of("topBar", Map.of("text", "Texto novo")));
        clock.advance(Duration.ofMinutes(1));
        assertThat(put(id, change).status()).isEqualTo(200);

        JsonNode changes = changes(id);

        assertChange(changes, "name", "Black Friday", "Black Friday 2026");
        assertThat(changes.path("priority").path("from").asInt()).isEqualTo(10);
        assertThat(changes.path("priority").path("to").asInt()).isEqualTo(20);
        assertChange(changes, "content.topBar.text", "Black Friday: até 40%", "Texto novo");
        assertChange(changes, "content.hero.title", "Black Friday Fausto", null); // o banner foi retirado
        for (String untouched : List.of("type", "status", "startsAt", "theme.dark.primary", "collections")) {
            assertThat(changes.has(untouched)).as(untouched).isFalse();
        }
    }

    @Test
    void theActionsRecordWhatTheyChanged() throws Exception {
        String id = create(complete("Black Friday")).body().path("id").asText();
        clock.advance(Duration.ofMinutes(1));
        action(id, "activate-now");

        JsonNode changes = changes(id);

        assertChange(changes, "status", "DRAFT", "PUBLISHED");
        assertChange(changes, "startsAt", "2026-11-27T03:00:00Z", clock.instant().toString());
        assertChange(changes, "publishedAt", null, clock.instant().toString());
        assertThat(changes.size()).isEqualTo(3);
    }

    @Test
    void changesInTheCollectionsAreDescribed() throws Exception {
        UUID a = product();
        UUID b = product();
        UUID c = product();
        JsonNode created = create(Map.of("collections", List.of(
                collection(null, "ofertas", a, b), collection(null, "sobra", a)))).body();
        String id = created.path("id").asText();
        assertThat(changes(id).path("collections").path("added")).isEqualTo(json.readTree("""
                [{"slug": "ofertas", "title": "Coleção", "products": 2},
                 {"slug": "sobra", "title": "Coleção", "products": 1}]
                """));
        String offers = created.path("collections").path(0).path("id").asText();
        clock.advance(Duration.ofMinutes(1));
        assertThat(put(id, Map.of("version", 0, "collections", List.of(
                collection(offers, "presentes", b, a, c), collection(null, "kits")))).status()).isEqualTo(200);

        JsonNode collections = changes(id).path("collections");

        assertThat(collections.path("added").path(0).path("slug").asText()).isEqualTo("kits");
        assertThat(collections.path("removed").path(0).path("slug").asText()).isEqualTo("sobra");
        JsonNode changed = collections.path("changed").path(0);
        assertThat(changed.path("slug").asText()).isEqualTo("presentes");
        assertChange(changed.path("changes"), "slug", "ofertas", "presentes");
        assertThat(changed.path("productsAdded").path("count").asInt()).isEqualTo(1);
        assertThat(changed.path("productsAdded").path("ids").path(0).asText()).isEqualTo(c.toString());
        assertThat(changed.path("productsReordered").asBoolean()).isTrue();
        assertThat(changed.has("productsRemoved")).isFalse();
    }

    @Test
    void aDeletedDraftKeepsItsHistory() throws Exception {
        String id = create(complete("Descartada")).body().path("id").asText();
        clock.advance(Duration.ofMinutes(1));
        assertThat(call("DELETE", BASE + "/" + id + "?version=0", admin.token(), null, Map.of()).status())
                .isEqualTo(204);

        JsonNode latest = history(id).path("content").path(0);

        assertThat(latest.path("action").asText()).isEqualTo("CAMPAIGN_DELETED");
        assertThat(details(latest).path("name").asText()).isEqualTo("Descartada");
        assertChange(details(latest).path("changes"), "theme.dark.primary", "#C1121F", null);
    }

    @Test
    void aDuplicationSaysWhereItCameFrom() throws Exception {
        String id = create(complete("Original")).body().path("id").asText();
        String copy = call("POST", BASE + "/" + id + "/duplicate", admin.token(), null,
                Map.of("Idempotency-Key", newKey())).body().path("id").asText();

        JsonNode details = details(history(copy).path("content").path(0));

        assertThat(details.path("sourceId").asText()).isEqualTo(id);
        assertChange(details.path("changes"), "name", null, "Original");
        assertChange(details.path("changes"), "status", null, "DRAFT");
    }

    // ───────────────────────────── registros grandes ─────────────────────────────

    @Test
    void theLargestEditStaysReadable() throws Exception {
        List<UUID> first = IntStream.range(0, 60).mapToObj(i -> product()).toList();
        List<UUID> second = IntStream.range(0, 60).mapToObj(i -> product()).toList();
        JsonNode created = create(Map.of("collections", IntStream.range(0, 8)
                .mapToObj(i -> collection(null, "c" + i, first.toArray(UUID[]::new))).toList())).body();
        String id = created.path("id").asText();
        Map<String, Object> change = new LinkedHashMap<>(complete("Tudo de uma vez"));
        change.put("version", 0);
        change.put("collections", IntStream.range(0, 8).mapToObj(i -> collection(
                created.path("collections").path(i).path("id").asText(), "c" + i, second.toArray(UUID[]::new)))
                .toList());
        clock.advance(Duration.ofMinutes(1));
        assertThat(put(id, change).status()).isEqualTo(200);

        JsonNode details = details(history(id).path("content").path(0)); // falha se o JSON estiver cortado

        assertThat(details.path("name").asText()).isEqualTo("Tudo de uma vez");
        assertChange(details.path("changes"), "name", null, "Tudo de uma vez");
    }

    @Test
    void detailsThatDoNotFitLoseTheLargestValuesButStayValidJson() throws Exception {
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("priority", Map.of("from", 1, "to", 2));
        for (int i = 0; i < 40; i++) {
            changes.put("field" + i, Map.of("from", "a".repeat(500), "to", "b".repeat(500)));
        }
        String id = UUID.randomUUID().toString();

        audit.recordSuccess(AuditAction.CAMPAIGN_UPDATED, "CAMPAIGN", id,
                new LinkedHashMap<>(Map.of("name", "Grande", "changes", changes)));

        JsonNode entry = history(id).path("content").path(0);
        assertThat(entry.path("details").asText().length()).isLessThanOrEqualTo(16_000);
        JsonNode details = details(entry);
        assertThat(details.path("truncated").asBoolean()).isTrue();
        assertThat(details.path("name").asText()).isEqualTo("Grande");
        assertThat(details.path("changes").path("priority").path("to").asInt()).isEqualTo(2);
        assertThat(details.path("changes").findValuesAsText("from")).isNotEmpty(); // nem tudo se perde
        assertThat(details.path("changes").toString()).contains("[omitted]");
    }

    // ───────────────────────────── rota ─────────────────────────────

    @Test
    void theHistoryIsPagedAndBelongsToOneCampaign() throws Exception {
        String id = create(complete("Uma")).body().path("id").asText();
        create(complete("Outra"));
        for (int i = 0; i < 2; i++) {
            clock.advance(Duration.ofMinutes(1));
            assertThat(put(id, edit(id, "Uma " + i)).status()).isEqualTo(200);
        }

        JsonNode second = get(BASE + "/" + id + "/history?size=2&page=1").body();

        assertThat(texts(second.path("content"), "action")).containsExactly("CAMPAIGN_CREATED");
        assertThat(second.path("page").path("totalElements").asInt()).isEqualTo(3);
        assertThat(second.path("page").path("totalPages").asInt()).isEqualTo(2);
        assertThat(history(id).path("page").path("size").asInt()).isEqualTo(20);
        assertThat(get(BASE + "/" + id + "/history?size=1000").body().path("page").path("size").asInt())
                .isEqualTo(100);
    }

    @Test
    void theHistoryIsOnlyForAdministrators() throws Exception {
        String url = BASE + "/" + create(complete("Natal")).body().path("id").asText() + "/history";

        assertThat(call("GET", url, null, null, Map.of()).status()).isEqualTo(401);
        assertThat(call("GET", url, signIn(Role.USER).token(), null, Map.of()).status()).isEqualTo(404);
    }

    @Test
    void aCampaignWithoutRecordsHasAnEmptyHistory_andAnUnknownOneIsNotFound() throws Exception {
        String unrecorded = campaigns.save(CampaignFixtures.draft()).getId().toString();

        assertThat(history(unrecorded).path("content")).isEmpty();
        Res unknown = get(BASE + "/" + UUID.randomUUID() + "/history");
        assertThat(unknown.status()).isEqualTo(404);
        assertThat(unknown.code()).isEqualTo("not_found");
    }

    // ───────────────────────────── helpers ─────────────────────────────

    private JsonNode history(String id) throws Exception {
        Res res = get(BASE + "/" + id + "/history");
        assertThat(res.status()).isEqualTo(200);
        return res.body();
    }

    private JsonNode details(JsonNode entry) throws Exception {
        return json.readTree(entry.path("details").asText());
    }

    /** As mudanças do registro mais recente. */
    private JsonNode changes(String id) throws Exception {
        return details(history(id).path("content").path(0)).path("changes");
    }

    private static void assertChange(JsonNode changes, String field, String from, String to) {
        assertThat(changes.has(field)).as(field).isTrue();
        assertThat(changes.path(field).path("from").asText(null)).as(field + " antes").isEqualTo(from);
        assertThat(changes.path(field).path("to").asText(null)).as(field + " depois").isEqualTo(to);
    }

    private void action(String id, String action) throws Exception {
        assertThat(call("POST", BASE + "/" + id + "/" + action, admin.token(), Map.of("version", version(id)),
                Map.of()).status()).as(action).isEqualTo(200);
    }

    private Set<String> writeRoutes() {
        Set<String> found = new HashSet<>();
        routes.getHandlerMethods().keySet().forEach(mapping -> mapping.getPatternValues().stream()
                .filter(pattern -> pattern.startsWith(BASE))
                .forEach(pattern -> mapping.getMethodsCondition().getMethods().stream()
                        .filter(method -> method != RequestMethod.GET)
                        .forEach(method -> found.add(method + " " + pattern))));
        return found;
    }

    private static List<String> texts(JsonNode entries, String field) {
        List<String> texts = new ArrayList<>();
        entries.forEach(entry -> texts.add(entry.path(field).asText()));
        return texts;
    }

    private static Map<String, Object> collection(String id, String slug, UUID... productIds) {
        Map<String, Object> collection = new LinkedHashMap<>();
        collection.put("id", id);
        collection.put("title", "Coleção");
        collection.put("slug", slug);
        collection.put("products", Arrays.stream(productIds).map(p -> Map.of("productId", p)).toList());
        return collection;
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
        return products.save(p).getId();
    }
}
