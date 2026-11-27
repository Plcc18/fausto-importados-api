package com.example.fausto_importados_api.controller;

import com.example.fausto_importados_api.model.Campaign;
import com.example.fausto_importados_api.model.User;
import com.example.fausto_importados_api.model.enums.CampaignStatus;
import com.example.fausto_importados_api.model.enums.CampaignType;
import com.example.fausto_importados_api.model.enums.Role;
import com.example.fausto_importados_api.repository.AuditLogRepository;
import com.example.fausto_importados_api.repository.CampaignRepository;
import com.example.fausto_importados_api.repository.UserRepository;
import com.example.fausto_importados_api.security.jwt.JwtService;
import com.example.fausto_importados_api.support.CampaignFixtures;
import com.example.fausto_importados_api.support.MutableClock;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.example.fausto_importados_api.support.CampaignFixtures.collection;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/** As rotas {@code /api/admin/campaigns}, com as exigências de produção para versão e Idempotency-Key ligadas. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:campaignapi;DB_CLOSE_DELAY=-1",
        "app.concurrency.require-version=true",
        "app.concurrency.require-idempotency-key=true"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CampaignAdminApiTest {

    private static final Instant NOW = Instant.parse("2026-11-20T12:00:00Z");
    private static final String BASE = "/api/admin/campaigns";
    private static final String PASSWORD = "senha-longa-de-teste";

    @TestConfiguration
    static class TestClockConfig {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(NOW);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired CampaignRepository campaigns;
    @Autowired UserRepository users;
    @Autowired AuditLogRepository auditLog;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JwtService jwtService;

    private record Admin(User user, String token) {
    }

    private record Res(int status, JsonNode body, Map<String, String> headers) {
        String code() {
            return body.path("code").asText();
        }

        List<String> fields() {
            List<String> names = new ArrayList<>();
            body.path("fields").forEach(f -> names.add(f.path("field").asText()));
            return names;
        }
    }

    private Admin admin;

    @BeforeEach
    void setUp() throws Exception {
        campaigns.deleteAll();
        admin = signIn(Role.ADMIN);
    }

    // ───────────────────────────── acesso ─────────────────────────────

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({
            "GET,  /api/admin/campaigns",
            "GET,  /api/admin/campaigns/00000000-0000-0000-0000-000000000001",
            "POST, /api/admin/campaigns",
            "PUT,  /api/admin/campaigns/00000000-0000-0000-0000-000000000001",
    })
    void onlyAdministratorsReachTheRoutes(String method, String path) throws Exception {
        Admin customer = signIn(Role.USER);

        assertThat(call(method, path, null, draftBody(), Map.of()).status()).isEqualTo(401);
        assertThat(call(method, path, "isto.nao.e-um-jwt", draftBody(), Map.of()).status()).isEqualTo(401);
        // em /api/admin/**, quem está autenticado mas não é admin nem fica sabendo que a rota existe
        assertThat(call(method, path, customer.token(), draftBody(),
                Map.of("Idempotency-Key", newKey())).status()).isEqualTo(404);
        assertThat(campaigns.count()).isZero();
    }

    // ───────────────────────────── criar ─────────────────────────────

    @Test
    void aCompleteCampaignIsCreatedAsADraft() throws Exception {
        Res res = create(complete("Black Friday"));

        assertThat(res.status()).isEqualTo(201);
        JsonNode c = res.body();
        assertThat(c.path("id").asText()).isNotBlank();
        assertThat(c.path("name").asText()).isEqualTo("Black Friday");
        assertThat(c.path("type").asText()).isEqualTo("BLACK_FRIDAY");
        assertThat(c.path("priority").asInt()).isEqualTo(10);
        assertThat(c.path("startsAt").asText()).isEqualTo("2026-11-27T03:00:00Z");
        assertThat(c.path("theme")).isEqualTo(json.valueToTree(CampaignFixtures.theme()));
        assertThat(c.path("content")).isEqualTo(json.valueToTree(CampaignFixtures.content()));
        assertThat(c.path("status").asText()).isEqualTo("DRAFT");
        assertThat(c.path("version").asInt(-1)).isZero();
        assertThat(c.path("createdAt").asText()).isEqualTo(NOW.toString());
        assertThat(c.path("createdBy").asText()).isEqualTo(admin.user().getEmail());
        assertThat(c.path("updatedBy").asText()).isEqualTo(admin.user().getEmail());
        assertThat(c.path("collections").isArray()).isTrue();
        // campo sem valor vem como null, não é omitido
        assertThat(c.has("phase") && c.get("phase").isNull()).isTrue();
        assertThat(c.has("publishedAt") && c.get("publishedAt").isNull()).isTrue();
        assertThat(auditLog.findByEntityTypeOrderByOccurredAtDesc("CAMPAIGN",
                org.springframework.data.domain.Pageable.unpaged()).getContent())
                .anyMatch(row -> row.getAction().equals("CAMPAIGN_CREATED")
                        && row.getEntityId().equals(c.path("id").asText()));
    }

    @Test
    void aDraftMayBeEmpty() throws Exception {
        Res res = create(Map.of());

        assertThat(res.status()).isEqualTo(201);
        assertThat(res.body().path("priority").asInt(-1)).isZero();
        for (String field : List.of("name", "description", "type", "startsAt", "endsAt", "theme", "content")) {
            assertThat(res.body().has(field)).as(field).isTrue();
            assertThat(res.body().get(field).isNull()).as(field).isTrue();
        }
    }

    @Test
    void textsAndDatesAreNormalized_andTheDescriptionKeepsItsLines() throws Exception {
        Res res = create(Map.of("name", "  Natal  ", "description", "linha 1\nlinha 2",
                "startsAt", "2026-12-01T00:00:00-03:00", "collections", List.of()));

        assertThat(res.body().path("name").asText()).isEqualTo("Natal");
        assertThat(res.body().path("description").asText()).isEqualTo("linha 1\nlinha 2");
        assertThat(res.body().path("startsAt").asText()).isEqualTo("2026-12-01T03:00:00Z");
    }

    @Test
    void allInvalidFieldsAreReportedAtOnce_andNothingIsSaved() throws Exception {
        Map<String, Object> body = complete("ab");
        body.put("description", "<b>interna</b>");
        body.put("priority", 101);
        body.put("endsAt", body.get("startsAt"));
        body.put("theme", Map.of("dark", Map.of("primary", "azul")));
        body.put("collections", List.of(Map.of("title", "Ofertas", "slug", "ofertas")));

        Res res = create(body);

        assertThat(res.status()).isEqualTo(400);
        assertThat(res.code()).isEqualTo("validation_failed");
        assertThat(res.fields()).containsExactly(
                "name", "description", "priority", "endsAt", "collections", "theme.dark.primary");
        assertThat(campaigns.count()).isZero();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            {"startsAt": "2026-12-01T00:00:00"}                                        | startsAt
            {"startsAt": "1999-12-31T23:59:59Z"}                                       | startsAt
            {"endsAt": "+999999-01-01T00:00:00Z"}                                      | endsAt
            {"startsAt": "2027-01-01T00:00:00Z", "endsAt": "2027-05-01T00:00:01Z"}     | endsAt
            {"priority": -1}                                                           | priority
            {"type": "CARNIVAL"}                                                       | type
            {"description": "nulo\\u0000"}                                             | description
            """)
    void aSingleBadValueIsPointedOut(String body, String field) throws Exception {
        Res res = create(json.readValue(body, Map.class));

        assertThat(res.status()).isEqualTo(400);
        assertThat(res.fields()).containsExactly(field);
    }

    @Test
    void aCampaignMayLastExactly120Days() throws Exception {
        assertThat(create(Map.of("startsAt", "2027-01-01T00:00:00Z", "endsAt", "2027-05-01T00:00:00Z")).status())
                .isEqualTo(201);
    }

    @Test
    void repeatingACreationWithTheSameKeyDoesNotCreateAgain() throws Exception {
        String key = newKey();

        Res first = create(complete("Natal"), key);
        Res repeated = create(complete("Natal"), key);

        assertThat(repeated.status()).isEqualTo(201);
        assertThat(repeated.body().path("id")).isEqualTo(first.body().path("id"));
        assertThat(repeated.headers()).containsEntry("Idempotent-Replayed", "true");
        assertThat(campaigns.count()).isEqualTo(1);
        Res other = create(complete("Outra"), key);
        assertThat(other.status()).isEqualTo(422);
        assertThat(other.code()).isEqualTo("idempotency_key_reused");
    }

    @Test
    void aRefusedCreationDoesNotSpendTheKey() throws Exception {
        String key = newKey();
        assertThat(create(Map.of("priority", 500), key).status()).isEqualTo(400);

        assertThat(create(Map.of("priority", 5), key).status()).isEqualTo(201);
    }

    @Test
    void creatingWithoutAKeyIsRefused() throws Exception {
        Res res = call("POST", BASE, admin.token(), draftBody(), Map.of());

        assertThat(res.status()).isEqualTo(400);
        assertThat(res.code()).isEqualTo("idempotency_key_required");
        assertThat(campaigns.count()).isZero();
    }

    // ───────────────────────────── detalhar ─────────────────────────────

    @Test
    void anUnknownCampaignAnswersNotFound() throws Exception {
        Res res = get(BASE + "/" + UUID.randomUUID());

        assertThat(res.status()).isEqualTo(404);
        assertThat(res.code()).isEqualTo("not_found");
    }

    @Test
    void theDetailBringsTheCollectionsInOrder() throws Exception {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        Campaign campaign = CampaignFixtures.complete();
        campaign.addCollection(collection("segunda", 1, a));
        campaign.addCollection(collection("primeira", 0, b, a));
        UUID id = campaigns.save(campaign).getId();

        JsonNode collections = get(BASE + "/" + id).body().path("collections");

        assertThat(collections).hasSize(2);
        assertThat(collections.path(0).path("slug").asText()).isEqualTo("primeira");
        JsonNode first = collections.path(0).path("products").path(0);
        assertThat(first.path("productId").asText()).isEqualTo(b.toString());
        assertThat(first.path("position").asInt(-1)).isZero();
        assertThat(first.has("discount") && first.get("discount").isNull()).isTrue();
    }

    // ───────────────────────────── editar ─────────────────────────────

    @Test
    void editingReplacesTheWholeDocumentAndRaisesTheVersion() throws Exception {
        String id = create(complete("Antiga")).body().path("id").asText();
        Admin other = signIn(Role.ADMIN);

        Res res = call("PUT", BASE + "/" + id, other.token(),
                Map.of("name", "Nova", "status", "PUBLISHED", "version", 0), Map.of());

        assertThat(res.status()).isEqualTo(200);
        assertThat(res.body().path("name").asText()).isEqualTo("Nova");
        assertThat(res.body().path("version").asInt()).isEqualTo(1);
        assertThat(res.body().path("status").asText()).isEqualTo("DRAFT"); // o status não muda por aqui
        assertThat(res.body().path("priority").asInt(-1)).isZero();
        assertThat(res.body().get("theme").isNull()).isTrue();
        assertThat(res.body().get("startsAt").isNull()).isTrue();
        assertThat(res.body().path("createdBy").asText()).isEqualTo(admin.user().getEmail());
        assertThat(res.body().path("updatedBy").asText()).isEqualTo(other.user().getEmail());
        assertThat(auditLog.findByEntityTypeOrderByOccurredAtDesc("CAMPAIGN",
                org.springframework.data.domain.Pageable.unpaged()).getContent())
                .anyMatch(row -> row.getAction().equals("CAMPAIGN_UPDATED") && row.getEntityId().equals(id));
    }

    @Test
    void anEditOverAnOldVersionIsRefused() throws Exception {
        String id = create(complete("Original")).body().path("id").asText();
        assertThat(put(id, Map.of("name", "Primeira", "version", 0)).status()).isEqualTo(200);

        Res stale = put(id, Map.of("name", "Atrasada", "version", 0));
        Res missing = put(id, Map.of("name", "Sem versão"));

        assertThat(stale.status()).isEqualTo(409);
        assertThat(stale.code()).isEqualTo("version_conflict");
        assertThat(missing.status()).isEqualTo(428);
        assertThat(missing.code()).isEqualTo("version_required");
        assertThat(get(BASE + "/" + id).body().path("name").asText()).isEqualTo("Primeira");
    }

    @Test
    void editingAnUnknownCampaignAnswersNotFound() throws Exception {
        assertThat(put(UUID.randomUUID().toString(), Map.of("version", 0)).code()).isEqualTo("not_found");
    }

    @Test
    void editingKeepsTheExistingCollections() throws Exception {
        Campaign campaign = CampaignFixtures.complete();
        campaign.addCollection(collection("ofertas", 0, UUID.randomUUID()));
        String id = campaigns.save(campaign).getId().toString();

        Res res = put(id, Map.of("name", "Nova", "version", 0));

        assertThat(res.body().path("collections")).hasSize(1);
    }

    @Test
    void archivedAndExpiredCampaignsCannotBeEdited() throws Exception {
        String archived = stored(CampaignStatus.ARCHIVED, 1, days(1), days(2));
        String expired = stored(CampaignStatus.PUBLISHED, 2, days(-10), NOW); // termina exatamente agora

        for (String id : List.of(archived, expired)) {
            Res res = put(id, edit(id, "Mudança"));
            assertThat(res.status()).isEqualTo(409);
            assertThat(res.code()).isEqualTo("invalid_transition");
        }
    }

    @Test
    void aDisabledCampaignCanBeEditedAndLeftIncomplete() throws Exception {
        String id = stored(CampaignStatus.DISABLED, 1, days(1), days(2));

        assertThat(put(id, Map.of("version", version(id))).status()).isEqualTo(200);
    }

    @ParameterizedTest
    @CsvSource({"1, 5", "-1, 5"}) // agendada e ativa
    void aPublishedCampaignCanBeEdited(int start, int end) throws Exception {
        String id = stored(CampaignStatus.PUBLISHED, 10, days(start), days(end));

        Res res = put(id, edit(id, "Nome novo"));

        assertThat(res.status()).isEqualTo(200);
        assertThat(res.body().path("status").asText()).isEqualTo("PUBLISHED");
        assertThat(res.body().path("phase").asText()).isEqualTo(start > 0 ? "SCHEDULED" : "ACTIVE");
    }

    @Test
    void editingAPublishedCampaignNeedsTheSecondFactor() throws Exception {
        String published = stored(CampaignStatus.PUBLISHED, 10, days(1), days(5));
        String draft = stored(CampaignStatus.DRAFT, 10, days(1), days(5));
        Map<String, Object> change = edit(published, "Nome novo");
        // o admin passa a ter 2FA; o token que já tinha continua válido
        User user = users.findById(admin.user().getId()).orElseThrow();
        user.setTotpEnabled(true);
        users.save(user);

        Res denied = put(published, change);

        assertThat(denied.status()).isEqualTo(403);
        assertThat(denied.code()).isEqualTo("reauth_required");
        assertThat(campaigns.findById(UUID.fromString(published)).orElseThrow().getName()).isNotEqualTo("Nome novo");
        assertThat(put(draft, edit(draft, "Rascunho")).status()).isEqualTo(200); // rascunho não pede

        String proof = jwtService.generateReauthToken(user, jwtService.parse(admin.token()).sessionId());
        Res allowed = call("PUT", BASE + "/" + published, admin.token(), change, Map.of("X-Reauth", proof));

        assertThat(allowed.status()).isEqualTo(200);
    }

    @Test
    void aPublishedCampaignCannotBeLeftIncomplete() throws Exception {
        String id = stored(CampaignStatus.PUBLISHED, 10, days(1), days(5));

        Res res = put(id, Map.of("theme", Map.of("dark", Map.of("primary", "#000000")),
                "content", Map.of("hero", Map.of("title", "Sem imagem")), "version", version(id)));

        assertThat(res.status()).isEqualTo(422);
        assertThat(res.code()).isEqualTo("publish_requirements");
        assertThat(res.fields()).containsExactly("name", "type", "startsAt", "endsAt", "theme.dark.background",
                "theme.dark.foreground", "theme.dark.primaryForeground", "theme.dark.accent",
                "content.hero.imageDesktop");
        assertThat(campaigns.findById(UUID.fromString(id)).orElseThrow().getName()).isNotNull();
    }

    @Test
    void aPublishedCampaignCannotBeMovedToThePast() throws Exception {
        String id = stored(CampaignStatus.PUBLISHED, 10, days(-5), days(5));
        Map<String, Object> change = edit(id, "Encerrar");
        change.put("endsAt", NOW.toString());

        Res res = put(id, change);

        assertThat(res.status()).isEqualTo(422);
        assertThat(res.fields()).containsExactly("endsAt");
    }

    @Test
    void aPublishedCampaignCannotBeMovedOntoAnother() throws Exception {
        String other = stored(CampaignStatus.PUBLISHED, 10, days(1), days(5));
        String id = stored(CampaignStatus.PUBLISHED, 10, days(10), days(15));
        Map<String, Object> change = edit(id, "Antecipada");
        change.put("startsAt", days(4).toString());

        Res res = put(id, change);

        assertThat(res.status()).isEqualTo(409);
        assertThat(res.code()).isEqualTo("campaign_conflict");
        assertThat(res.body().path("conflictingCampaign").path("id").asText()).isEqualTo(other);
        assertThat(campaigns.findById(UUID.fromString(id)).orElseThrow().getStartsAt()).isEqualTo(days(10));
    }

    // ───────────────────────────── listar ─────────────────────────────

    @Test
    void theListIsPaged_draftsWithoutDateFirst_thenTheNewestStart() throws Exception {
        String old = named("Antiga", CampaignStatus.PUBLISHED, CampaignType.EASTER, days(-30), days(-20));
        String next = named("Futura", CampaignStatus.PUBLISHED, CampaignType.CHRISTMAS, days(20), days(30));
        String undated = named("Sem data", CampaignStatus.DRAFT, null, null, null);

        Res res = get(BASE);

        assertThat(res.status()).isEqualTo(200);
        assertThat(ids(res)).containsExactly(undated, next, old);
        assertThat(res.body().path("page").path("size").asInt()).isEqualTo(20);
        assertThat(res.body().path("page").path("number").asInt(-1)).isZero();
        assertThat(res.body().path("page").path("totalElements").asInt()).isEqualTo(3);
        assertThat(res.body().path("page").path("totalPages").asInt()).isEqualTo(1);
    }

    @Test
    void theSummaryHasOnlyWhatTheListShows() throws Exception {
        Campaign campaign = CampaignFixtures.complete();
        campaign.addCollection(collection("ofertas", 0));
        campaign.addCollection(collection("presentes", 1));
        campaigns.save(campaign);

        JsonNode item = get(BASE).body().path("content").path(0);

        List<String> keys = new ArrayList<>();
        item.fieldNames().forEachRemaining(keys::add);
        assertThat(keys).containsExactlyInAnyOrder("id", "name", "type", "status", "phase", "priority", "startsAt",
                "endsAt", "updatedAt", "updatedBy", "collectionCount", "version");
        assertThat(item.path("collectionCount").asInt()).isEqualTo(2);
    }

    @Test
    void theListIsFiltered() throws Exception {
        String expired = named("Páscoa", CampaignStatus.PUBLISHED, CampaignType.EASTER, days(-30), days(-20));
        String active = named("Black Friday", CampaignStatus.PUBLISHED, CampaignType.BLACK_FRIDAY, days(-1), days(3));
        String scheduled = named("Natal 100%", CampaignStatus.PUBLISHED, CampaignType.CHRISTMAS, days(20), days(30));
        String draft = named("Natal_rascunho", CampaignStatus.DRAFT, CampaignType.CHRISTMAS, days(20), days(30));
        String disabled = named("Desativada", CampaignStatus.DISABLED, CampaignType.CUSTOM, days(40), days(50));

        assertThat(ids(get(BASE + "?status=DRAFT,DISABLED"))).containsExactlyInAnyOrder(draft, disabled);
        assertThat(ids(get(BASE + "?type=CHRISTMAS,EASTER")))
                .containsExactlyInAnyOrder(expired, scheduled, draft);
        assertThat(ids(get(BASE + "?phase=ACTIVE"))).containsExactly(active);
        // a fase só existe em campanha publicada
        assertThat(ids(get(BASE + "?phase=SCHEDULED,EXPIRED"))).containsExactlyInAnyOrder(scheduled, expired);
        assertThat(ids(get(BASE + "?phase=SCHEDULED&status=DRAFT"))).isEmpty();
        assertThat(ids(get(BASE + "?q=natal"))).containsExactlyInAnyOrder(scheduled, draft);
        assertThat(ids(get(BASE + "?q=%25"))).containsExactly(scheduled); // "%" é texto, não curinga
        assertThat(ids(get(BASE + "?q=_"))).containsExactly(draft);
        assertThat(ids(get(BASE + "?q=natal&status=PUBLISHED&type=CHRISTMAS"))).containsExactly(scheduled);
    }

    @Test
    void thePeriodFilterKeepsWhatCrossesTheInterval() throws Exception {
        String campaign = named("Janeiro", CampaignStatus.DRAFT, null, days(10), days(20));
        named("Sem data", CampaignStatus.DRAFT, null, null, null);

        assertThat(ids(get(BASE + "?from=" + days(15) + "&to=" + days(16)))).containsExactly(campaign);
        assertThat(ids(get(BASE + "?from=" + days(19)))).containsExactly(campaign);
        assertThat(ids(get(BASE + "?to=" + days(11)))).containsExactly(campaign);
        assertThat(ids(get(BASE + "?from=" + days(20)))).isEmpty(); // o fim é exclusivo
        assertThat(ids(get(BASE + "?to=" + days(10)))).isEmpty();
    }

    @Test
    void thePageSizeStaysWithinTheLimits() throws Exception {
        for (int i = 0; i < 3; i++) {
            named("Campanha " + i, CampaignStatus.DRAFT, null, days(i), days(i + 1));
        }

        Res second = get(BASE + "?size=2&page=1");

        assertThat(ids(second)).hasSize(1);
        assertThat(second.body().path("page").path("totalPages").asInt()).isEqualTo(2);
        assertThat(get(BASE + "?size=1000").body().path("page").path("size").asInt()).isEqualTo(100);
        assertThat(get(BASE + "?size=0&page=-3").body().path("page").path("size").asInt()).isEqualTo(1);
    }

    @ParameterizedTest
    @CsvSource({
            "status=ATIVA,                                      status",
            "phase=ACTIVE%2CNUNCA,                              phase",
            "type=CARNIVAL,                                     type",
            "from=ontem,                                        from",
            "to=2026-12-01,                                     to",
            "from=2026-12-02T00:00:00Z&to=2026-12-01T00:00:00Z, to",
    })
    void invalidFiltersAreRefused(String query, String field) throws Exception {
        Res res = call("GET", BASE + "?" + query, admin.token(), null, Map.of());

        assertThat(res.status()).isEqualTo(400);
        assertThat(res.code()).isEqualTo("validation_failed");
        assertThat(res.fields()).containsExactly(field);
    }

    // ───────────────────────────── helpers ─────────────────────────────

    private static Instant days(int days) {
        return NOW.plus(days, ChronoUnit.DAYS);
    }

    private static String newKey() {
        return UUID.randomUUID().toString();
    }

    private static Map<String, Object> draftBody() {
        return Map.of("name", "Rascunho");
    }

    private Map<String, Object> complete(String name) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("description", null);
        body.put("type", "BLACK_FRIDAY");
        body.put("priority", 10);
        body.put("startsAt", "2026-11-27T03:00:00Z");
        body.put("endsAt", "2026-12-01T03:00:00Z");
        body.put("theme", CampaignFixtures.theme());
        body.put("content", CampaignFixtures.content());
        return body;
    }

    /** Grava direto no banco: as rotas que publicam, desativam e arquivam ainda não existem. */
    private String stored(CampaignStatus status, int priority, Instant start, Instant end) {
        Campaign campaign = CampaignFixtures.complete();
        campaign.setStatus(status);
        campaign.setPriority(priority);
        campaign.setStartsAt(start);
        campaign.setEndsAt(end);
        return campaigns.save(campaign).getId().toString();
    }

    private String named(String name, CampaignStatus status, CampaignType type, Instant start, Instant end) {
        Campaign campaign = status == CampaignStatus.PUBLISHED ? CampaignFixtures.complete() : CampaignFixtures.draft();
        campaign.setName(name);
        campaign.setType(type);
        campaign.setStatus(status);
        campaign.setStartsAt(start);
        campaign.setEndsAt(end);
        return campaigns.save(campaign).getId().toString();
    }

    private int version(String id) throws Exception {
        return get(BASE + "/" + id).body().path("version").asInt();
    }

    /** O documento atual com outro nome, como o painel enviaria. */
    private Map<String, Object> edit(String id, String name) throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> body = json.convertValue(get(BASE + "/" + id).body(), LinkedHashMap.class);
        body.put("name", name);
        body.put("collections", List.of());
        return body;
    }

    private static List<String> ids(Res res) {
        List<String> ids = new ArrayList<>();
        res.body().path("content").forEach(item -> ids.add(item.path("id").asText()));
        return ids;
    }

    private Res create(Object body) throws Exception {
        return create(body, newKey());
    }

    private Res create(Object body, String key) throws Exception {
        return call("POST", BASE, admin.token(), body, Map.of("Idempotency-Key", key));
    }

    private Res put(String id, Object body) throws Exception {
        return call("PUT", BASE + "/" + id, admin.token(), body, Map.of());
    }

    private Res get(String url) throws Exception {
        return call("GET", url, admin.token(), null, Map.of());
    }

    private Res call(String method, String url, String token, Object body, Map<String, String> headers)
            throws Exception {
        // URI pronta: um template codificaria de novo o que já vem codificado (%25, %2C)
        MockHttpServletRequestBuilder builder = request(HttpMethod.valueOf(method), java.net.URI.create(url));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        headers.forEach(builder::header);
        if (body != null && !method.equals("GET")) {
            builder.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body));
        }
        var response = mvc.perform(builder).andReturn().getResponse();
        String text = response.getContentAsString(StandardCharsets.UTF_8);
        JsonNode node = text.isBlank() ? json.createObjectNode() : json.readTree(text);
        Map<String, String> received = new LinkedHashMap<>();
        response.getHeaderNames().forEach(name -> received.put(name, response.getHeader(name)));
        return new Res(response.getStatus(), node, received);
    }

    private Admin signIn(Role role) throws Exception {
        User u = new User();
        u.setEmail("campanha-" + UUID.randomUUID() + "@test.com");
        u.setPassword(passwordEncoder.encode(PASSWORD));
        u.setRole(role);
        u.setActive(true);
        User saved = users.save(u);
        Res res = call("POST", "/auth/login", null, Map.of("email", saved.getEmail(), "password", PASSWORD), Map.of());
        assertThat(res.status()).as("login").isEqualTo(200);
        return new Admin(saved, res.body().path("accessToken").asText());
    }
}
