package com.example.fausto_importados_api.controller;

import com.example.fausto_importados_api.model.Product;
import com.example.fausto_importados_api.model.enums.Category;
import com.example.fausto_importados_api.repository.ProductRepository;
import com.example.fausto_importados_api.services.CampaignCatalog;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** Coleções e produtos participantes, gravados junto com a campanha. */
class CampaignCollectionsApiTest extends CampaignApiTestSupport {

    @Autowired ProductRepository products;
    @Autowired CampaignCatalog catalog;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;

    // ───────────────────────────── criar e editar ─────────────────────────────

    @Test
    void aCampaignIsCreatedWithItsCollections() throws Exception {
        UUID a = product();
        UUID b = product();

        Res res = create(body(collection("ofertas", a, b), collection("presentes", b)));

        assertThat(res.status()).isEqualTo(201);
        JsonNode collections = res.body().path("collections");
        assertThat(slugs(collections)).containsExactly("ofertas", "presentes");
        assertThat(productIds(collections.path(0))).containsExactly(a, b);
        for (int i = 0; i < 2; i++) {
            assertThat(collections.path(i).path("id").asText()).isNotBlank();
            assertThat(collections.path(i).path("position").asInt(-1)).isEqualTo(i);
        }
        assertThat(collections.path(0).path("products").path(1).path("position").asInt()).isEqualTo(1);
        assertThat(detail(res.body().path("id").asText())).isEqualTo(res.body());
    }

    @Test
    void thePositionsDecideTheOrderAndAreRenumbered() throws Exception {
        UUID a = product();
        UUID b = product();
        UUID c = product();
        Map<String, Object> last = collection("ultima");
        last.put("position", 40);
        Map<String, Object> first = collection("primeira");
        first.put("position", 7);
        first.put("products", List.of(
                Map.of("productId", a, "position", 9),
                Map.of("productId", b, "position", 3),
                Map.of("productId", c, "position", 3))); // empate: fica a ordem da lista

        JsonNode collections = create(body(last, first)).body().path("collections");

        assertThat(slugs(collections)).containsExactly("primeira", "ultima");
        assertThat(collections.path(0).path("position").asInt(-1)).isZero();
        assertThat(collections.path(1).path("position").asInt()).isEqualTo(1);
        assertThat(productIds(collections.path(0))).containsExactly(b, c, a);
        assertThat(collections.path(0).path("products").path(2).path("position").asInt()).isEqualTo(2);
    }

    @Test
    void editingUpdatesCreatesReordersAndRemoves() throws Exception {
        UUID a = product();
        UUID b = product();
        UUID c = product();
        Res created = create(body(collection("ofertas", a, b), collection("presentes", c), collection("sobra", a)));
        String id = created.body().path("id").asText();
        JsonNode before = created.body().path("collections");

        Map<String, Object> offers = existing(before.path(0), "presentes", c, a); // troca o identificador
        Map<String, Object> gifts = existing(before.path(1), "ofertas");          // e fica sem produtos
        Map<String, Object> change = body(gifts, collection("sobra", b), offers); // "sobra" é reaproveitado
        change.put("version", 0);
        Res res = put(id, change);

        assertThat(res.status()).isEqualTo(200);
        assertThat(res.body().path("version").asInt()).isEqualTo(1);
        JsonNode after = res.body().path("collections");
        assertThat(slugs(after)).containsExactly("ofertas", "sobra", "presentes");
        assertThat(after.path(0).path("id")).isEqualTo(before.path(1).path("id"));
        assertThat(after.path(2).path("id")).isEqualTo(before.path(0).path("id"));
        assertThat(after.path(1).path("id")).isNotEqualTo(before.path(2).path("id"));
        assertThat(productIds(after.path(0))).isEmpty();
        assertThat(productIds(after.path(1))).containsExactly(b);
        assertThat(productIds(after.path(2))).containsExactly(c, a);
        assertThat(detail(id)).isEqualTo(res.body());
        assertThat(count("campaign_collections")).isEqualTo(3);
        assertThat(count("campaign_collection_products")).isEqualTo(3);
    }

    @Test
    void sendingTheSameDocumentBackKeepsEverything() throws Exception {
        Res created = create(body(collection("ofertas", product(), product())));
        String id = created.body().path("id").asText();

        Res res = put(id, edit(id, "Outro nome"));

        assertThat(res.status()).isEqualTo(200);
        assertThat(res.body().path("collections")).isEqualTo(created.body().path("collections"));
    }

    @Test
    void anEditWithoutCollectionsRemovesThem() throws Exception {
        String id = create(body(collection("ofertas", product()))).body().path("id").asText();

        Res res = put(id, Map.of("name", "Sem coleções", "version", 0));

        assertThat(res.body().path("collections")).isEmpty();
        assertThat(count("campaign_collections")).isZero();
        assertThat(count("campaign_collection_products")).isZero();
    }

    @Test
    void aChangeOnlyInTheProductsRaisesTheVersion() throws Exception {
        JsonNode created = create(body(collection("ofertas", product()))).body();
        String id = created.path("id").asText();
        Map<String, Object> change = body(existing(created.path("collections").path(0), "ofertas", product()));
        change.put("version", 0); // nada muda na campanha além do produto
        assertThat(put(id, change).body().path("version").asInt()).isEqualTo(1);

        Res stale = put(id, change);

        assertThat(stale.status()).isEqualTo(409);
        assertThat(stale.code()).isEqualTo("version_conflict");
    }

    // ───────────────────────────── recusas ─────────────────────────────

    @Test
    void unknownMissingAndRepeatedProductsAreRefused() throws Exception {
        UUID a = product();
        Map<String, Object> bad = collection("ofertas");
        bad.put("products", Arrays.asList(
                Map.of("productId", a),
                Map.of("productId", UUID.randomUUID()),
                Map.of(),
                Map.of("productId", a),
                null,
                Map.of("productId", product(), "position", -1)));

        Res res = create(body(bad));

        assertThat(res.status()).isEqualTo(400);
        assertThat(res.code()).isEqualTo("validation_failed");
        assertThat(messages(res)).containsExactly(
                "collections[0].products[1].productId: Produto não encontrado.",
                "collections[0].products[2].productId: Escolha o produto.",
                "collections[0].products[3].productId: Este produto já está na coleção.",
                "collections[0].products[4]: Item vazio.",
                "collections[0].products[5].position: Use um número a partir de 0.");
        assertThat(campaigns.count()).isZero();
    }

    @Test
    void theSameProductMayBeInTwoCollections() throws Exception {
        UUID a = product();

        assertThat(create(body(collection("ofertas", a), collection("presentes", a))).status()).isEqualTo(201);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Ofertas", "com espaco", "-ofertas", "ofertas-", "a--b", "a_b", "promoção", "a/b",
            "a?b=1", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "  "})
    void onlySimpleIdentifiersAreAccepted(String slug) throws Exception {
        Res res = create(body(collection(slug)));

        assertThat(res.status()).isEqualTo(400);
        assertThat(res.fields()).containsExactly("collections[0].slug");
    }

    @Test
    void titlesAndIdentifiersFollowTheirRules() throws Exception {
        Map<String, Object> untitled = collection("ofertas");
        untitled.remove("title");
        Map<String, Object> html = collection("presentes");
        html.put("title", "<b>Presentes</b>");
        Map<String, Object> longTitle = collection("ofertas"); // identificador repetido
        longTitle.put("title", "a".repeat(61));
        Map<String, Object> negative = collection("kits");
        negative.put("position", -2);

        Res res = create(body(untitled, html, longTitle, negative, null));

        assertThat(res.fields()).containsExactly("collections[0].title", "collections[1].title",
                "collections[2].title", "collections[2].slug", "collections[3].position", "collections[4]");
        assertThat(messages(res)).contains(
                "collections[2].slug: Já existe outra coleção com este identificador.");
    }

    @Test
    void aCampaignHasAtMost8CollectionsOf60Products() throws Exception {
        List<UUID> many = IntStream.range(0, 61).mapToObj(i -> product()).toList();
        Object[] eight = IntStream.range(0, 8)
                .mapToObj(i -> collection("c" + i, many.subList(0, 60).toArray(UUID[]::new))).toArray();

        assertThat(create(body(eight)).status()).isEqualTo(201);

        Object[] nine = IntStream.range(0, 9).mapToObj(i -> collection("c" + i)).toArray();
        assertThat(create(body(nine)).fields()).containsExactly("collections");
        assertThat(create(body(collection("cheia", many.toArray(UUID[]::new)))).fields())
                .containsExactly("collections[0].products");
    }

    @Test
    void aCollectionIdMustBelongToTheCampaignAndAppearOnce() throws Exception {
        JsonNode other = create(body(collection("alheia"))).body();
        Res created = create(body(collection("ofertas")));
        String id = created.body().path("id").asText();
        JsonNode own = created.body().path("collections").path(0);

        Map<String, Object> change = body(existing(own, "ofertas"), existing(own, "repetida"),
                existing(other.path("collections").path(0), "de-outra"));
        change.put("version", 0);
        Res res = put(id, change);

        assertThat(res.fields()).containsExactly("collections[1].id", "collections[2].id");
        assertThat(detail(id)).isEqualTo(created.body());
        // na criação nenhum id é válido
        assertThat(create(body(existing(own, "copiada"))).fields()).containsExactly("collections[0].id");
    }

    @Test
    void discountsAreRefusedUntilTheSecondStage() throws Exception {
        UUID a = product();
        Map<String, Object> discounted = collection("ofertas");
        discounted.put("products", List.of(
                Map.of("productId", a, "discount", Map.of("type", "PERCENT", "value", 20))));

        Res res = create(body(discounted));

        assertThat(res.status()).isEqualTo(400);
        assertThat(res.code()).isEqualTo("discount_not_supported");
        assertThat(res.fields()).containsExactly("collections[0].products[0].discount");
        assertThat(campaigns.count()).isZero();
    }

    // ───────────────────────────── produtos ─────────────────────────────

    @Test
    void aDeactivatedProductStaysInThePanelButNotInTheStore() throws Exception {
        UUID active = product();
        UUID inactive = product();
        Product p = products.findById(inactive).orElseThrow();
        p.setActive(false);
        products.save(p);

        Res res = create(body(collection("ofertas", inactive, active), collection("so-inativo", inactive)));

        assertThat(res.status()).isEqualTo(201);
        assertThat(productIds(res.body().path("collections").path(0))).containsExactly(inactive, active);
        UUID id = UUID.fromString(res.body().path("id").asText());
        var store = tx.execute(status -> catalog.publicCollections(campaigns.findDetailById(id).orElseThrow()));
        assertThat(store).extracting("slug").containsExactly("ofertas", "so-inativo");
        assertThat(store.get(0).productIds()).containsExactly(active);
        assertThat(store.get(1).productIds()).isEmpty();
    }

    @Test
    void nothingDoneToACampaignChangesTheProducts() throws Exception {
        UUID a = product();
        UUID b = product();
        List<Map<String, Object>> before = productRows();
        Map<String, Object> input = body(collection("ofertas", a, b));
        input.putAll(complete("Black Friday"));
        String id = create(input).body().path("id").asText();

        Map<String, Object> change = edit(id, "Editada");
        change.put("collections", List.of(collection("outra", b)));
        assertThat(put(id, change).status()).isEqualTo(200);
        for (String action : List.of("publish", "activate-now", "disable", "reactivate", "disable", "archive")) {
            assertThat(call("POST", BASE + "/" + id + "/" + action, admin.token(),
                    Map.of("version", version(id)), Map.of()).status()).as(action).isEqualTo(200);
        }
        String copy = call("POST", BASE + "/" + id + "/duplicate", admin.token(), null,
                Map.of("Idempotency-Key", newKey())).body().path("id").asText();
        assertThat(call("DELETE", BASE + "/" + copy + "?version=0", admin.token(), null, Map.of()).status())
                .isEqualTo(204);

        assertThat(productRows()).isEqualTo(before);
    }

    // ───────────────────────────── helpers ─────────────────────────────

    private UUID product() {
        Product p = new Product();
        p.setName("Perfume " + UUID.randomUUID());
        p.setBrand("Marca");
        p.setDescription("Descrição");
        p.setCategory(Category.UNISSEX);
        p.setOlfactiveFamily("FLORAL");
        p.setSize("100ml");
        p.setPrice(new BigDecimal("199.90"));
        p.setOriginalPrice(new BigDecimal("249.90"));
        p.setImage("https://example.com/p.webp");
        p.setInStock(true);
        p.setStockQuantity(3);
        return products.save(p).getId();
    }

    private static Map<String, Object> collection(String slug, UUID... productIds) {
        Map<String, Object> collection = new LinkedHashMap<>();
        collection.put("title", "Coleção");
        collection.put("slug", slug);
        collection.put("products", Arrays.stream(productIds).map(id -> Map.of("productId", id)).toList());
        return collection;
    }

    private static Map<String, Object> existing(JsonNode current, String slug, UUID... productIds) {
        Map<String, Object> collection = collection(slug, productIds);
        collection.put("id", current.path("id").asText());
        return collection;
    }

    private static Map<String, Object> body(Object... collections) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("collections", Arrays.asList(collections));
        return body;
    }

    private static List<String> slugs(JsonNode collections) {
        List<String> slugs = new ArrayList<>();
        collections.forEach(c -> slugs.add(c.path("slug").asText()));
        return slugs;
    }

    private static List<UUID> productIds(JsonNode collection) {
        List<UUID> ids = new ArrayList<>();
        collection.path("products").forEach(p -> ids.add(UUID.fromString(p.path("productId").asText())));
        return ids;
    }

    private static List<String> messages(Res res) {
        List<String> messages = new ArrayList<>();
        res.body().path("fields").forEach(f ->
                messages.add(f.path("field").asText() + ": " + f.path("message").asText()));
        return messages;
    }

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    private List<Map<String, Object>> productRows() {
        return jdbc.queryForList("select * from products order by id");
    }
}
