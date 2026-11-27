package com.example.fausto_importados_api.controller;

import com.example.fausto_importados_api.model.Campaign;
import com.example.fausto_importados_api.model.Product;
import com.example.fausto_importados_api.model.enums.CampaignStatus;
import com.example.fausto_importados_api.model.enums.Category;
import com.example.fausto_importados_api.model.enums.Role;
import com.example.fausto_importados_api.repository.ProductRepository;
import com.example.fausto_importados_api.support.CampaignFixtures;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.example.fausto_importados_api.support.CampaignFixtures.collection;
import static org.assertj.core.api.Assertions.assertThat;

/** A rota que a loja consulta e a prévia do painel: quem acessa e o que fica exposto. */
class PublicCampaignApiTest extends CampaignApiTestSupport {

    private static final String ACTIVE = "/api/campaigns/active";

    private static final String[] PUBLIC_FIELDS = {
            "id", "type", "startsAt", "endsAt", "updatedAt", "theme", "content", "collections"};

    @Autowired ProductRepository products;

    // ───────────────────────────── acesso ─────────────────────────────

    @Test
    void anyoneCanAskForTheActiveCampaign() throws Exception {
        String customer = signIn(Role.USER).token();
        stored(CampaignStatus.PUBLISHED, 1, days(-1), days(5));

        Res anonymous = visit(null);

        assertThat(anonymous.status()).isEqualTo(200);
        for (String token : new String[]{customer, admin.token(), "isto.nao.e-um-jwt"}) {
            Res res = visit(token);
            assertThat(res.status()).isEqualTo(200);
            assertThat(res.body()).isEqualTo(anonymous.body()); // todos veem o mesmo
        }
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({
            "POST,   /api/campaigns/active",
            "PUT,    /api/campaigns/active",
            "DELETE, /api/campaigns/active",
            "GET,    /api/campaigns",
            "GET,    /api/campaigns/00000000-0000-0000-0000-000000000001",
            "GET,    /api/campaigns/active/preview",
    })
    void nothingElseUnderCampaignsIsPublic(String method, String path) throws Exception {
        assertThat(call(method, path, null, Map.of(), Map.of()).status()).isEqualTo(401);
    }

    @Test
    void thePreviewIsOnlyForAdministrators() throws Exception {
        String id = stored(CampaignStatus.DRAFT, 1, days(1), days(5));
        String url = BASE + "/" + id + "/preview";

        assertThat(call("GET", url, null, null, Map.of()).status()).isEqualTo(401);
        assertThat(call("GET", url, signIn(Role.USER).token(), null, Map.of()).status()).isEqualTo(404);
        assertThat(get(url).status()).isEqualTo(200);
    }

    // ───────────────────────────── o que sai ─────────────────────────────

    @Test
    void withoutACampaignOnAirTheAnswerSaysSo() throws Exception {
        Res res = visit(null);

        assertThat(res.status()).isEqualTo(200);
        assertThat(keys(res.body())).containsExactlyInAnyOrder("serverTime", "nextChangeAt", "campaign");
        assertThat(res.body().path("serverTime").asText()).isEqualTo(NOW.toString());
        assertThat(res.body().get("campaign").isNull()).isTrue();
        assertThat(res.body().get("nextChangeAt").isNull()).isTrue();
    }

    @Test
    void onlyPublicDataLeavesTheStoreRoute() throws Exception {
        UUID second = product(true);
        UUID first = product(true);
        UUID inactive = product(false);
        Campaign campaign = secretive(CampaignStatus.PUBLISHED, days(-1), days(5));
        campaign.addCollection(collection("presentes", 1, second));
        campaign.addCollection(collection("ofertas", 0, first, inactive, second));
        UUID id = campaigns.save(campaign).getId();

        Res res = visit(null);

        JsonNode shown = res.body().path("campaign");
        assertThat(keys(shown)).containsExactlyInAnyOrder(PUBLIC_FIELDS);
        assertThat(shown.path("id").asText()).isEqualTo(id.toString());
        assertThat(shown.path("type").asText()).isEqualTo("BLACK_FRIDAY");
        assertThat(shown.path("startsAt").asText()).isEqualTo(days(-1).toString());
        assertThat(shown.path("theme")).isEqualTo(json.valueToTree(CampaignFixtures.theme()));
        assertThat(shown.path("content")).isEqualTo(json.valueToTree(CampaignFixtures.content()));
        assertThat(res.body().path("nextChangeAt").asText()).isEqualTo(days(5).toString());
        JsonNode offers = shown.path("collections").path(0);
        assertThat(keys(offers)).containsExactlyInAnyOrder("title", "slug", "position", "productIds");
        assertThat(offers.path("slug").asText()).isEqualTo("ofertas");
        assertThat(texts(offers.path("productIds"))).containsExactly(first.toString(), second.toString());
        assertThat(texts(shown.path("collections").path(1).path("productIds"))).containsExactly(second.toString());
        assertNothingPrivate(res, inactive);
    }

    @ParameterizedTest(name = "{0} de {1} a {2}")
    @CsvSource({
            "DRAFT,     -1,  5",
            "PUBLISHED,  1,  5", // agendada
            "PUBLISHED, -10, -1", // encerrada
            "DISABLED,  -1,  5",
            "ARCHIVED,  -1,  5",
    })
    void whatIsNotOnAirNeverLeaks(CampaignStatus status, int start, int end) throws Exception {
        Campaign campaign = secretive(status, days(start), days(end));
        campaign.addCollection(collection("segredo", 0, product(true)));
        String id = campaigns.save(campaign).getId().toString();

        Res res = visit(null);

        assertThat(res.status()).isEqualTo(200);
        assertThat(res.body().get("campaign").isNull()).isTrue();
        assertThat(res.body().toString()).doesNotContain(id, "segredo", "BLACK_FRIDAY", "res.cloudinary.com");
        // da agendada, só o horário em que a resposta vai mudar
        boolean scheduled = status == CampaignStatus.PUBLISHED && start > 0;
        assertThat(res.body().path("nextChangeAt").asText(null))
                .isEqualTo(scheduled ? days(start).toString() : null);
    }

    @Test
    void theStrongestCampaignIsShown() throws Exception {
        stored(CampaignStatus.PUBLISHED, 1, days(-1), days(5));
        String strong = stored(CampaignStatus.PUBLISHED, 90, days(-1), days(5));

        assertThat(visit(null).body().path("campaign").path("id").asText()).isEqualTo(strong);
    }

    @Test
    void theStoreFollowsWhatTheAdminDoes() throws Exception {
        String id = create(complete("Black Friday")).body().path("id").asText();
        assertThat(visit(null).body().get("campaign").isNull()).isTrue();

        action(id, "activate-now");
        assertThat(visit(null).body().path("campaign").path("id").asText()).isEqualTo(id);

        action(id, "disable");
        assertThat(visit(null).body().get("campaign").isNull()).isTrue();
    }

    // ───────────────────────────── prévia ─────────────────────────────

    @ParameterizedTest(name = "{0} de {1} a {2}")
    @CsvSource({
            "DRAFT,      1,  5",
            "PUBLISHED,  1,  5",
            "PUBLISHED, -1,  5",
            "PUBLISHED, -10, -1",
            "DISABLED,  -1,  5",
            "ARCHIVED,  -1,  5",
    })
    void thePreviewShowsAnyCampaignTheWayTheStoreWould(CampaignStatus status, int start, int end) throws Exception {
        UUID active = product(true);
        UUID inactive = product(false);
        Campaign campaign = secretive(status, days(start), days(end));
        campaign.addCollection(collection("ofertas", 0, inactive, active));
        String id = campaigns.save(campaign).getId().toString();

        Res res = get(BASE + "/" + id + "/preview");

        assertThat(res.status()).isEqualTo(200);
        assertThat(keys(res.body())).containsExactlyInAnyOrder("serverTime", "nextChangeAt", "campaign");
        assertThat(res.body().path("serverTime").asText()).isEqualTo(NOW.toString());
        assertThat(res.body().get("nextChangeAt").isNull()).isTrue();
        JsonNode shown = res.body().path("campaign");
        assertThat(keys(shown)).containsExactlyInAnyOrder(PUBLIC_FIELDS);
        assertThat(shown.path("id").asText()).isEqualTo(id);
        assertThat(texts(shown.path("collections").path(0).path("productIds"))).containsExactly(active.toString());
        assertThat(res.headers().get("Cache-Control")).contains("no-store");
        assertNothingPrivate(res, inactive);
    }

    @Test
    void anEmptyDraftCanBePreviewed() throws Exception {
        String id = create(Map.of()).body().path("id").asText();

        JsonNode shown = get(BASE + "/" + id + "/preview").body().path("campaign");

        assertThat(keys(shown)).containsExactlyInAnyOrder(PUBLIC_FIELDS);
        for (String field : List.of("type", "startsAt", "endsAt", "theme", "content")) {
            assertThat(shown.get(field).isNull()).as(field).isTrue();
        }
        assertThat(shown.path("collections")).isEmpty();
    }

    @Test
    void thePreviewChangesNothingAndTheStoreDoesNotSeeIt() throws Exception {
        String id = stored(CampaignStatus.DRAFT, 1, days(-1), days(5));
        JsonNode before = detail(id);
        requireSecondFactor(); // a prévia não pede

        assertThat(get(BASE + "/" + id + "/preview").status()).isEqualTo(200);

        assertThat(detail(id)).isEqualTo(before);
        assertThat(visit(null).body().get("campaign").isNull()).isTrue();
    }

    @Test
    void thePreviewOfAnUnknownCampaignAnswersNotFound() throws Exception {
        Res res = get(BASE + "/" + UUID.randomUUID() + "/preview");

        assertThat(res.status()).isEqualTo(404);
        assertThat(res.code()).isEqualTo("not_found");
    }

    // ───────────────────────────── helpers ─────────────────────────────

    private Res visit(String token) throws Exception {
        return call("GET", ACTIVE, token, null, Map.of());
    }

    private void action(String id, String action) throws Exception {
        assertThat(call("POST", BASE + "/" + id + "/" + action, admin.token(), Map.of("version", version(id)),
                Map.of()).status()).as(action).isEqualTo(200);
    }

    /** Campanha completa com marcas reconhecíveis em tudo o que não pode vazar. */
    private Campaign secretive(CampaignStatus status, Instant start, Instant end) {
        Campaign campaign = CampaignFixtures.complete();
        campaign.setName("NOME-INTERNO");
        campaign.setDescription("DESCRICAO-INTERNA");
        campaign.setPriority(77);
        campaign.registerChange(NOW, "autor-interno@test.com");
        campaign.setCreatedBy("criador-interno@test.com");
        campaign.setPublishedAt(Instant.parse("2020-01-02T03:04:05Z"));
        campaign.setStatus(status);
        campaign.setStartsAt(start);
        campaign.setEndsAt(end);
        return campaign;
    }

    private static void assertNothingPrivate(Res res, UUID inactiveProduct) {
        assertThat(res.body().toString()).doesNotContain("NOME-INTERNO", "DESCRICAO-INTERNA", "autor-interno",
                "criador-interno", "2020-01-02T03:04:05Z", "priority", "status", "version", "createdBy",
                "discount", inactiveProduct.toString());
    }

    private UUID product(boolean active) {
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
        p.setActive(active);
        return products.save(p).getId();
    }

    private static List<String> keys(JsonNode node) {
        List<String> keys = new ArrayList<>();
        node.fieldNames().forEachRemaining(keys::add);
        return keys;
    }

    private static List<String> texts(JsonNode array) {
        List<String> texts = new ArrayList<>();
        array.forEach(item -> texts.add(item.asText()));
        return texts;
    }
}
