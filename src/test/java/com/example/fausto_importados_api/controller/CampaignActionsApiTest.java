package com.example.fausto_importados_api.controller;

import com.example.fausto_importados_api.model.Campaign;
import com.example.fausto_importados_api.model.enums.CampaignStatus;
import com.example.fausto_importados_api.model.enums.Role;
import com.example.fausto_importados_api.support.CampaignFixtures;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.example.fausto_importados_api.support.CampaignFixtures.collection;
import static org.assertj.core.api.Assertions.assertThat;

/** Publicar, ativar agora, desativar, reativar, arquivar, excluir e duplicar. */
class CampaignActionsApiTest extends CampaignApiTestSupport {

    enum Situation { DRAFT, SCHEDULED, ACTIVE, EXPIRED, DISABLED, ARCHIVED }

    enum Operation { EDIT, PUBLISH, ACTIVATE_NOW, DISABLE, REACTIVATE, ARCHIVE, DELETE }

    // ───────────────────────────── transições ─────────────────────────────

    /** A tabela do contrato (§7.3): o que a campanha vira, ou "-" se a ação é recusada. */
    @ParameterizedTest(name = "{0} + {1} → {2}")
    @CsvSource(delimiter = '|', textBlock = """
            DRAFT     | EDIT         | DRAFT
            DRAFT     | PUBLISH      | PUBLISHED SCHEDULED
            DRAFT     | ACTIVATE_NOW | PUBLISHED ACTIVE
            DRAFT     | DISABLE      | -
            DRAFT     | REACTIVATE   | -
            DRAFT     | ARCHIVE      | ARCHIVED
            DRAFT     | DELETE       | GONE
            SCHEDULED | EDIT         | PUBLISHED SCHEDULED
            SCHEDULED | PUBLISH      | -
            SCHEDULED | ACTIVATE_NOW | PUBLISHED ACTIVE
            SCHEDULED | DISABLE      | DISABLED
            SCHEDULED | REACTIVATE   | -
            SCHEDULED | ARCHIVE      | -
            SCHEDULED | DELETE       | -
            ACTIVE    | EDIT         | PUBLISHED ACTIVE
            ACTIVE    | PUBLISH      | -
            ACTIVE    | ACTIVATE_NOW | -
            ACTIVE    | DISABLE      | DISABLED
            ACTIVE    | REACTIVATE   | -
            ACTIVE    | ARCHIVE      | -
            ACTIVE    | DELETE       | -
            EXPIRED   | EDIT         | -
            EXPIRED   | PUBLISH      | -
            EXPIRED   | ACTIVATE_NOW | -
            EXPIRED   | DISABLE      | -
            EXPIRED   | REACTIVATE   | -
            EXPIRED   | ARCHIVE      | ARCHIVED
            EXPIRED   | DELETE       | -
            DISABLED  | EDIT         | DISABLED
            DISABLED  | PUBLISH      | -
            DISABLED  | ACTIVATE_NOW | -
            DISABLED  | DISABLE      | -
            DISABLED  | REACTIVATE   | PUBLISHED SCHEDULED
            DISABLED  | ARCHIVE      | ARCHIVED
            DISABLED  | DELETE       | -
            ARCHIVED  | EDIT         | -
            ARCHIVED  | PUBLISH      | -
            ARCHIVED  | ACTIVATE_NOW | -
            ARCHIVED  | DISABLE      | -
            ARCHIVED  | REACTIVATE   | -
            ARCHIVED  | ARCHIVE      | -
            ARCHIVED  | DELETE       | -
            """)
    void everyTransition(Situation situation, Operation operation, String outcome) throws Exception {
        String id = in(situation);
        JsonNode before = detail(id);

        Res res = perform(operation, id, before.path("version").asInt());

        if (outcome.equals("-")) {
            assertThat(res.status()).isEqualTo(409);
            assertThat(res.code()).isEqualTo("invalid_transition");
            assertThat(detail(id)).isEqualTo(before);
        } else if (outcome.equals("GONE")) {
            assertThat(res.status()).isEqualTo(204);
            assertThat(get(BASE + "/" + id).status()).isEqualTo(404);
        } else {
            assertThat(res.status()).isEqualTo(200);
            String[] expected = outcome.split(" ");
            assertThat(res.body().path("status").asText()).isEqualTo(expected[0]);
            assertThat(res.body().path("phase").asText(null)).isEqualTo(expected.length > 1 ? expected[1] : null);
            assertThat(res.body().path("version").asInt()).isEqualTo(before.path("version").asInt() + 1);
            assertThat(detail(id)).isEqualTo(res.body());
        }
    }

    @Test
    void aWholeLifeIsRecorded() throws Exception {
        String id = create(complete("Black Friday")).body().path("id").asText();

        for (Operation operation : List.of(Operation.PUBLISH, Operation.ACTIVATE_NOW, Operation.DISABLE,
                Operation.REACTIVATE, Operation.DISABLE, Operation.ARCHIVE)) {
            assertThat(perform(operation, id, version(id)).status()).as(operation.name()).isEqualTo(200);
        }
        String copy = duplicate(id, newKey()).body().path("id").asText();
        assertThat(perform(Operation.DELETE, copy, 0).status()).isEqualTo(204);

        assertThat(auditedActions(id)).containsExactlyInAnyOrder("CAMPAIGN_CREATED", "CAMPAIGN_PUBLISHED",
                "CAMPAIGN_ACTIVATED", "CAMPAIGN_DISABLED", "CAMPAIGN_REACTIVATED", "CAMPAIGN_DISABLED",
                "CAMPAIGN_ARCHIVED");
        assertThat(auditedActions(copy)).containsExactlyInAnyOrder("CAMPAIGN_DUPLICATED", "CAMPAIGN_DELETED");
    }

    // ───────────────────────────── publicar ─────────────────────────────

    @Test
    void publishingRecordsWhenAndByWhom() throws Exception {
        String id = in(Situation.DRAFT);
        Admin other = signIn(Role.ADMIN);

        Res res = call("POST", BASE + "/" + id + "/publish", other.token(), Map.of("version", 0), Map.of());

        assertThat(res.body().path("publishedAt").asText()).isEqualTo(NOW.toString());
        assertThat(res.body().path("updatedBy").asText()).isEqualTo(other.user().getEmail());
        assertThat(res.body().path("startsAt").asText()).isEqualTo(days(1).toString()); // publicar não muda as datas
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            PUBLISH      | DRAFT    | name,type,startsAt,endsAt,theme.dark
            ACTIVATE_NOW | DRAFT    | name,type,endsAt,theme.dark
            REACTIVATE   | DISABLED | name,type,startsAt,endsAt,theme.dark
            """)
    void anIncompleteCampaignCannotGoLive(Operation operation, CampaignStatus status, String missing)
            throws Exception {
        Campaign empty = CampaignFixtures.draft();
        empty.setStatus(status);
        String id = campaigns.save(empty).getId().toString();
        JsonNode before = detail(id);

        Res res = perform(operation, id, 0);

        assertThat(res.status()).isEqualTo(422);
        assertThat(res.code()).isEqualTo("publish_requirements");
        assertThat(res.fields()).containsExactly(missing.split(","));
        assertThat(detail(id)).isEqualTo(before);
    }

    @ParameterizedTest
    @CsvSource({"PUBLISH, DRAFT", "ACTIVATE_NOW, DRAFT", "REACTIVATE, DISABLED"})
    void aCampaignWhoseEndHasPassedCannotGoLive(Operation operation, CampaignStatus status) throws Exception {
        String id = stored(status, 1, days(-5), NOW);

        Res res = perform(operation, id, 0);

        assertThat(res.status()).isEqualTo(422);
        assertThat(res.fields()).containsExactly("endsAt");
        assertThat(detail(id).path("status").asText()).isEqualTo(status.name());
    }

    // ───────────────────────────── ativar agora ─────────────────────────────

    @Test
    void activatingNowBringsTheStartForward() throws Exception {
        String future = stored(CampaignStatus.DRAFT, 1, days(10), days(20));
        String undated = stored(CampaignStatus.DRAFT, 2, null, days(20));
        String late = stored(CampaignStatus.DRAFT, 3, days(-2), days(20));

        for (String id : List.of(future, undated)) {
            Res res = perform(Operation.ACTIVATE_NOW, id, 0);
            assertThat(res.body().path("startsAt").asText()).isEqualTo(NOW.toString());
            assertThat(res.body().path("phase").asText()).isEqualTo("ACTIVE");
            assertThat(res.body().path("publishedAt").asText()).isEqualTo(NOW.toString());
        }
        // um início que já passou não é adiado
        assertThat(perform(Operation.ACTIVATE_NOW, late, 0).body().path("startsAt").asText())
                .isEqualTo(days(-2).toString());
    }

    @Test
    void activatingAScheduledCampaignKeepsTheDateItWasPublished() throws Exception {
        Campaign scheduled = CampaignFixtures.complete();
        scheduled.setStatus(CampaignStatus.PUBLISHED);
        scheduled.setStartsAt(days(10));
        scheduled.setEndsAt(days(20));
        scheduled.setPublishedAt(days(-3));
        String id = campaigns.save(scheduled).getId().toString();

        Res res = perform(Operation.ACTIVATE_NOW, id, 0);

        assertThat(res.body().path("publishedAt").asText()).isEqualTo(days(-3).toString());
    }

    @ParameterizedTest
    @EnumSource(value = CampaignStatus.class, names = {"DRAFT", "PUBLISHED"})
    void activatingNowCannotStretchTheCampaignPast120Days(CampaignStatus status) throws Exception {
        String id = stored(status, 1, days(100), days(200));

        Res res = perform(Operation.ACTIVATE_NOW, id, 0);

        assertThat(res.status()).isEqualTo(422);
        assertThat(res.fields()).containsExactly("endsAt");
        assertThat(res.body().path("fields").path(0).path("message").asText())
                .isEqualTo("A campanha pode durar no máximo 120 dias.");
        assertThat(detail(id).path("startsAt").asText()).isEqualTo(days(100).toString());
    }

    // ───────────────────────────── conflito ─────────────────────────────

    @ParameterizedTest
    @CsvSource({"PUBLISH, DRAFT, 2, 8", "REACTIVATE, DISABLED, 2, 8", "ACTIVATE_NOW, DRAFT, 20, 30"})
    void goingLiveOverAnotherCampaignIsRefused(Operation operation, CampaignStatus status, int start, int end)
            throws Exception {
        // no último caso o período gravado não cruza: o conflito aparece quando o início vem para agora
        String onAir = stored(CampaignStatus.PUBLISHED, 5, days(-1), days(5));
        String id = stored(status, 5, days(start), days(end));
        JsonNode before = detail(id);

        Res res = perform(operation, id, 0);

        assertThat(res.status()).isEqualTo(409);
        assertThat(res.code()).isEqualTo("campaign_conflict");
        assertThat(res.body().path("conflictingCampaign").path("id").asText()).isEqualTo(onAir);
        assertThat(detail(id)).isEqualTo(before);
    }

    @Test
    void disablingFreesThePeriod() throws Exception {
        String onAir = stored(CampaignStatus.PUBLISHED, 5, days(-1), days(5));
        String next = stored(CampaignStatus.DRAFT, 5, days(2), days(8));
        assertThat(perform(Operation.PUBLISH, next, 0).status()).isEqualTo(409);

        assertThat(perform(Operation.DISABLE, onAir, 0).status()).isEqualTo(200);

        assertThat(perform(Operation.PUBLISH, next, 0).status()).isEqualTo(200);
    }

    @Test
    void archivingAnExpiredCampaignFreesItsPeriod() throws Exception {
        String expired = stored(CampaignStatus.PUBLISHED, 5, days(-10), days(-1));
        String next = stored(CampaignStatus.DRAFT, 5, days(-2), days(8));
        assertThat(perform(Operation.PUBLISH, next, 0).status()).isEqualTo(409);

        assertThat(perform(Operation.ARCHIVE, expired, 0).status()).isEqualTo(200);

        assertThat(perform(Operation.PUBLISH, next, 0).status()).isEqualTo(200);
    }

    // ───────────────────────────── versão, 2FA e acesso ─────────────────────────────

    @ParameterizedTest
    @CsvSource({"PUBLISH, DRAFT", "ACTIVATE_NOW, SCHEDULED", "DISABLE, ACTIVE", "REACTIVATE, DISABLED",
            "ARCHIVE, EXPIRED", "DELETE, DRAFT"})
    void everyActionChecksTheVersion(Operation operation, Situation situation) throws Exception {
        String id = in(situation);
        JsonNode before = detail(id);

        Res stale = perform(operation, id, 7);
        Res missing = perform(operation, id, null);

        assertThat(stale.status()).isEqualTo(409);
        assertThat(stale.code()).isEqualTo("version_conflict");
        assertThat(missing.status()).isEqualTo(428);
        assertThat(missing.code()).isEqualTo("version_required");
        assertThat(detail(id)).isEqualTo(before);
    }

    @ParameterizedTest
    @EnumSource(value = Operation.class, names = "EDIT", mode = EnumSource.Mode.EXCLUDE)
    void anActionOnAnUnknownCampaignAnswersNotFound(Operation operation) throws Exception {
        Res res = perform(operation, UUID.randomUUID().toString(), 0);

        assertThat(res.status()).isEqualTo(404);
        assertThat(res.code()).isEqualTo("not_found");
    }

    @ParameterizedTest
    @CsvSource({"PUBLISH, DRAFT", "ACTIVATE_NOW, DRAFT", "REACTIVATE, DISABLED", "ARCHIVE, DISABLED",
            "DELETE, DRAFT"})
    void goingLiveArchivingAndDeletingNeedTheSecondFactor(Operation operation, Situation situation) throws Exception {
        String id = in(situation);
        JsonNode before = detail(id);
        String proof = requireSecondFactor();

        Res denied = perform(operation, id, 0);

        assertThat(denied.status()).isEqualTo(403);
        assertThat(denied.code()).isEqualTo("reauth_required");
        assertThat(detail(id)).isEqualTo(before);
        assertThat(perform(operation, id, 0, Map.of("X-Reauth", proof)).status()).isIn(200, 204);
    }

    @Test
    void disablingAndDuplicatingDoNotNeedTheSecondFactor() throws Exception {
        String id = in(Situation.ACTIVE);
        requireSecondFactor();

        assertThat(perform(Operation.DISABLE, id, 0).status()).isEqualTo(200);
        assertThat(duplicate(id, newKey()).status()).isEqualTo(201);
    }

    @ParameterizedTest
    @CsvSource({"POST, /publish", "POST, /activate-now", "POST, /disable", "POST, /reactivate", "POST, /archive",
            "POST, /duplicate", "DELETE, ?version=0"})
    void onlyAdministratorsReachTheActions(String method, String suffix) throws Exception {
        String id = in(Situation.DRAFT);
        JsonNode before = detail(id);
        Admin customer = signIn(Role.USER);
        String url = BASE + "/" + id + suffix;
        Map<String, String> headers = Map.of("Idempotency-Key", newKey());

        assertThat(call(method, url, null, Map.of("version", 0), headers).status()).isEqualTo(401);
        assertThat(call(method, url, customer.token(), Map.of("version", 0), headers).status()).isEqualTo(404);
        assertThat(detail(id)).isEqualTo(before);
        assertThat(campaigns.count()).isEqualTo(1);
    }

    // ───────────────────────────── duplicar e excluir ─────────────────────────────

    @Test
    void duplicatingCopiesEverythingButTheDates() throws Exception {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        Campaign original = CampaignFixtures.complete();
        original.setStatus(CampaignStatus.ARCHIVED); // o caso de repetir a campanha do ano passado
        original.setPublishedAt(days(-300));
        original.addCollection(collection("mais-vendidos", 1, a));
        original.addCollection(collection("ofertas", 0, b, a));
        String id = campaigns.save(original).getId().toString();
        JsonNode source = detail(id);
        Admin other = signIn(Role.ADMIN);

        Res res = call("POST", BASE + "/" + id + "/duplicate", other.token(), null,
                Map.of("Idempotency-Key", newKey()));

        assertThat(res.status()).isEqualTo(201);
        JsonNode copy = res.body();
        assertThat(copy.path("id").asText()).isNotEqualTo(id);
        for (String field : List.of("name", "description", "type", "priority", "theme", "content")) {
            assertThat(copy.get(field)).as(field).isEqualTo(source.get(field));
        }
        for (String field : List.of("startsAt", "endsAt", "publishedAt", "phase")) {
            assertThat(copy.get(field).isNull()).as(field).isTrue();
        }
        assertThat(copy.path("status").asText()).isEqualTo("DRAFT");
        assertThat(copy.path("version").asInt(-1)).isZero();
        assertThat(copy.path("createdBy").asText()).isEqualTo(other.user().getEmail());
        assertThat(copy.path("createdAt").asText()).isEqualTo(NOW.toString());
        assertThat(copy.path("collections")).hasSize(2);
        for (int i = 0; i < 2; i++) {
            JsonNode copied = copy.path("collections").path(i);
            JsonNode expected = source.path("collections").path(i);
            assertThat(copied.path("id")).isNotEqualTo(expected.path("id"));
            for (String field : List.of("title", "slug", "position", "products")) {
                assertThat(copied.get(field)).as(field).isEqualTo(expected.get(field));
            }
        }
        assertThat(detail(id)).isEqualTo(source);
    }

    @Test
    void repeatingADuplicationWithTheSameKeyDoesNotCopyAgain() throws Exception {
        String first = in(Situation.DRAFT);
        String second = stored(CampaignStatus.DRAFT, 2, null, null);
        String key = newKey();

        Res once = duplicate(first, key);
        Res again = duplicate(first, key);

        assertThat(again.status()).isEqualTo(201);
        assertThat(again.body().path("id")).isEqualTo(once.body().path("id"));
        assertThat(again.headers()).containsEntry("Idempotent-Replayed", "true");
        // a chave vale por campanha: em outra, copia
        assertThat(duplicate(second, key).body().path("id")).isNotEqualTo(once.body().path("id"));
        assertThat(campaigns.count()).isEqualTo(4);
    }

    @Test
    void duplicatingWithoutAKeyIsRefused() throws Exception {
        String id = in(Situation.DRAFT);

        Res res = call("POST", BASE + "/" + id + "/duplicate", admin.token(), null, Map.of());

        assertThat(res.status()).isEqualTo(400);
        assertThat(res.code()).isEqualTo("idempotency_key_required");
        assertThat(campaigns.count()).isEqualTo(1);
    }

    @Test
    void deletingADraftTakesItsCollections() throws Exception {
        Campaign draft = CampaignFixtures.draft();
        draft.addCollection(collection("ofertas", 0, UUID.randomUUID()));
        String id = campaigns.save(draft).getId().toString();

        assertThat(perform(Operation.DELETE, id, 0).status()).isEqualTo(204);

        assertThat(campaigns.count()).isZero();
    }

    // ───────────────────────────── helpers ─────────────────────────────

    private String in(Situation situation) {
        return switch (situation) {
            case DRAFT -> stored(CampaignStatus.DRAFT, 1, days(1), days(5));
            case SCHEDULED -> stored(CampaignStatus.PUBLISHED, 1, days(1), days(5));
            case ACTIVE -> stored(CampaignStatus.PUBLISHED, 1, days(-1), days(5));
            case EXPIRED -> stored(CampaignStatus.PUBLISHED, 1, days(-10), days(-5));
            case DISABLED -> stored(CampaignStatus.DISABLED, 1, days(1), days(5));
            case ARCHIVED -> stored(CampaignStatus.ARCHIVED, 1, days(1), days(5));
        };
    }

    private Res perform(Operation operation, String id, Integer version) throws Exception {
        return perform(operation, id, version, Map.of());
    }

    /** @param version nula = o pedido vai sem ela */
    private Res perform(Operation operation, String id, Integer version, Map<String, String> headers)
            throws Exception {
        String url = BASE + "/" + id;
        Object body = version == null ? null : Map.of("version", version);
        if (operation == Operation.EDIT) {
            Map<String, Object> document = edit(id, "Editada");
            document.put("version", version);
            return call("PUT", url, admin.token(), document, headers);
        }
        if (operation == Operation.DELETE) {
            return call("DELETE", version == null ? url : url + "?version=" + version, admin.token(), null, headers);
        }
        String action = operation.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
        return call("POST", url + "/" + action, admin.token(), body, headers);
    }

    private Res duplicate(String id, String key) throws Exception {
        return call("POST", BASE + "/" + id + "/duplicate", admin.token(), null, Map.of("Idempotency-Key", key));
    }
}
