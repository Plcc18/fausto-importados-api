package com.example.fausto_importados_api.concurrency;

import com.example.fausto_importados_api.model.User;
import com.example.fausto_importados_api.model.enums.Role;
import com.example.fausto_importados_api.security.mfa.TotpService;
import org.apache.commons.codec.binary.Base32;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;

/** Sessões (refresh token), contas de administrador, 2FA e bloqueio de login sob concorrência. */
class SessionAccountConcurrencyTest extends ConcurrencyTestSupport {

    @Autowired TotpService totp;
    @Autowired Clock clock;

    // ───────────────────────────── refresh token ─────────────────────────────

    @Test
    void manyRefreshesWithTheSameTokenRotateItExactlyOnce() {
        Session session = admin();

        List<Res> results = concurrently(25, i -> () -> refresh(session.refreshCookie()));

        assertThat(statuses(results)).containsOnly(200);
        assertThat(results.stream().filter(r -> r.headers().firstValue("Set-Cookie").isPresent())).hasSize(1);
        assertThat(count("select count(*) from refresh_tokens where user_id = ?", session.id())).isEqualTo(2);
        assertThat(liveTokens(session.id())).isEqualTo(1);
    }

    @Test
    void aRefreshRacingWithLogoutNeverLeavesTheSessionAlive() throws Exception {
        Session session = admin();

        raceAgainstSlowRotation(session, () -> call("POST", "/auth/logout", session, null));

        assertThat(unrevokedTokens(session.id())).isZero();
    }

    @Test
    void aRefreshRacingWithLogoutAllNeverLeavesTheSessionAlive() throws Exception {
        Session session = admin();

        raceAgainstSlowRotation(session, () -> call("POST", "/auth/logout-all", session, null));

        assertThat(unrevokedTokens(session.id())).isZero();
    }

    @Test
    void aRefreshRacingWithDeactivationNeverLeavesTheSessionAlive() throws Exception {
        Session boss = admin();
        Session victim = admin();

        raceAgainstSlowRotation(victim,
                () -> call("PATCH", "/api/user/" + victim.id() + "/active", boss, Map.of("active", false)));

        assertThat(unrevokedTokens(victim.id())).isZero();
    }

    private void raceAgainstSlowRotation(Session session, Callable<Res> revocation) throws Exception {
        try (AutoCloseable ignored = slowDown("refresh_tokens", "insert", 0.7)) {
            List<Res> results = concurrently(2, i -> i == 0
                    ? () -> refresh(session.refreshCookie())
                    : () -> {
                        pause(250);
                        return revocation.call();
                    });
            assertThat(results.get(1).status()).isLessThan(300);
        }
    }

    // ───────────────────────────── contas ─────────────────────────────

    @Test
    void twoAdminsDeactivatingEachOtherAlwaysLeaveOneActive() throws Exception {
        jdbc.update("update users set active = false where role = 'ADMIN'");
        Session first = admin();
        Session second = admin();

        List<Res> results;
        try (AutoCloseable ignored = slowDown("users", "update", 0.5)) {
            results = concurrently(2, i -> {
                Session actor = i == 0 ? first : second;
                Session target = i == 0 ? second : first;
                return () -> call("PATCH", "/api/user/" + target.id() + "/active", actor, Map.of("active", false));
            });
        } finally {
            jdbc.update("update users set active = true where email = 'admin@test.com'");
        }

        assertThat(countStatus(results, 200)).as(statuses(results).toString()).isEqualTo(1);
        assertThat(countStatus(results, 409)).isEqualTo(1);
        assertThat(count("select count(*) from users where active and id in (?, ?)", first.id(), second.id())).isEqualTo(1);
    }

    @Test
    void changingThePasswordNeverReactivatesAnAccountDeactivatedMeanwhile() {
        Session session = admin();

        Res res = whileLocked(
                "select 1 from users where id = '" + session.id() + "' for update",
                () -> call("POST", "/auth/password", session,
                        Map.of("currentPassword", PASSWORD, "newPassword", "outra-senha-bem-longa-987")),
                List.of("update users set active = false where id = '" + session.id() + "'"));

        assertThat(jdbc.queryForObject("select active from users where id = ?", Boolean.class, session.id())).isFalse();
        assertThat(res.status()).isEqualTo(409);
    }

    @Test
    void aStaleAccountEditIsRefused() {
        Session boss = admin();
        User target = newUser(Role.ADMIN);
        long seen = call("GET", "/api/user/" + target.getId(), boss, null).body().path("version").asLong(0);
        assertThat(call("PUT", "/api/user/" + target.getId(), boss,
                Map.of("email", "novo-" + UUID.randomUUID() + "@test.com", "version", seen)).status()).isEqualTo(200);

        Res res = call("PATCH", "/api/user/" + target.getId() + "/active", boss, Map.of("active", false, "version", seen));

        assertThat(res.status()).isEqualTo(409);
        assertThat(res.body().path("code").asText()).isEqualTo("version_conflict");
    }

    @Test
    void theSameEmailRegisteredManyTimesAtOnceCreatesOneAccountAndOneAuditRow() {
        Session boss = admin();
        String email = "unico-" + UUID.randomUUID() + "@test.com";

        List<Res> results = concurrently(20, i -> () -> call("POST", "/api/user", boss,
                Map.of("email", i % 2 == 0 ? email : email.toUpperCase())));

        assertThat(countStatus(results, 201)).as(statuses(results).toString()).isEqualTo(1);
        assertThat(countStatus(results, 409)).isEqualTo(19);
        assertThat(count("select count(*) from users where lower(email) = ?", email)).isEqualTo(1);
        assertThat(count("select count(*) from audit_log where action = 'ADMIN_CREATED' and actor_id = ?", boss.id()))
                .isEqualTo(1);
    }

    // ───────────────────────────── 2FA ─────────────────────────────

    @Test
    void confirmingTheMfaSetupManyTimesAtOnceEnrollsOnce() {
        User user = newUser(Role.ADMIN);
        String challenge = jwtService.generateMfaChallenge(user);
        Res setup = call("POST", "/auth/mfa/setup", null, Map.of("challenge", challenge));
        assertThat(setup.status()).isEqualTo(200);
        String code = totp.codeAt(new Base32().decode(setup.body().get("secret").asText()), clock.instant());

        List<Res> results = concurrently(10, i -> () -> call("POST", "/auth/mfa/setup/confirm", null,
                Map.of("challenge", challenge, "code", code), Map.of("X-Forwarded-For", freshIp())));

        assertThat(countStatus(results, 200)).as(statuses(results).toString()).isEqualTo(1);
        assertThat(count("select count(*) from mfa_recovery_codes where user_id = ? and used_at is null", user.getId()))
                .isEqualTo(10);
        assertThat(count("select count(*) from refresh_tokens where user_id = ?", user.getId())).isEqualTo(1);
    }

    @Test
    void regeneratingRecoveryCodesInParallelLeavesOneSetOfTen() {
        Session session = admin();
        jdbc.update("update users set totp_enabled = true where id = ?", session.id());
        String proof = jwtService.generateReauthToken(session.user(), jwtService.parse(session.access()).sessionId());

        List<Res> results = concurrently(10, i -> () -> call("POST", "/auth/mfa/recovery-codes", session, null,
                Map.of("X-Reauth", proof)));

        assertThat(statuses(results)).containsOnly(200);
        assertThat(count("select count(*) from mfa_recovery_codes where user_id = ? and used_at is null", session.id()))
                .isEqualTo(10);
    }

    // ───────────────────────────── bloqueio de login ─────────────────────────────

    @Test
    void parallelPasswordGuessesCannotExceedTheLockThreshold() {
        User user = newUser(Role.ADMIN);
        String ip = freshIp();

        List<Res> results = concurrently(30, i -> () -> call("POST", "/auth/login", null,
                Map.of("email", user.getEmail(), "password", "errada-" + i), Map.of("X-Forwarded-For", ip)));

        assertThat(countStatus(results, 401)).as(statuses(results).toString()).isEqualTo(5);
        assertThat(countStatus(results, 429)).isEqualTo(25);
    }

    @Test
    void aSuccessfulLoginLeavesTheFailureCountAndItsWindowAsTheyWere() {
        User user = newUser(Role.ADMIN);
        String ip = freshIp();
        call("POST", "/auth/login", null, Map.of("email", user.getEmail(), "password", "errada"),
                Map.of("X-Forwarded-For", ip));
        jdbc.update("update auth_throttle set updated_at = updated_at - interval '5 minutes' where throttle_key = ?",
                "i:" + ip);
        var before = jdbc.queryForMap("select failures, updated_at from auth_throttle where throttle_key = ?", "i:" + ip);

        assertThat(call("POST", "/auth/login", null, Map.of("email", user.getEmail(), "password", PASSWORD),
                Map.of("X-Forwarded-For", ip)).status()).isEqualTo(200);

        assertThat(jdbc.queryForMap("select failures, updated_at from auth_throttle where throttle_key = ?", "i:" + ip))
                .isEqualTo(before);
    }

    // ───────────────────────────── constraints: última linha de defesa ─────────────────────────────

    @Test
    void theDatabaseItselfRefusesInvalidData() {
        Session session = admin();
        var product = newProduct(1);
        String order = placeOrder(product, 1);

        List<String> accepted = new ArrayList<>();
        for (String sql : List.of(
                "update products set stock_quantity = -1 where id = '" + product.getId() + "'",
                "update products set price = 0 where id = '" + product.getId() + "'",
                "update orders set total = -1 where id = '" + order + "'",
                "update orders set status = 'QUALQUER' where id = '" + order + "'",
                "update order_items set quantity = 0 where order_id = '" + order + "'",
                "update order_items set unit_price = -1 where order_id = '" + order + "'",
                "update order_items set product_id = gen_random_uuid() where order_id = '" + order + "'",
                "update users set role = 'ROOT' where id = '" + session.id() + "'",
                "insert into users (id, email, password, role) values (gen_random_uuid(), '"
                        + session.user().getEmail().toUpperCase() + "', 'x', 'USER')",
                "insert into refresh_tokens (id, user_id, family_id, token_hash, created_at, last_used_at, expires_at, "
                        + "absolute_expires_at) select gen_random_uuid(), user_id, family_id, md5(random()::text), now(), "
                        + "now(), now(), now() from refresh_tokens where user_id = '" + session.id() + "'")) {
            try {
                jdbc.update(sql);
                accepted.add(sql);
            } catch (org.springframework.dao.DataIntegrityViolationException expected) {
                // é o que se quer
            }
        }
        assertThat(accepted).isEmpty();
    }

    // ───────────────────────────── helpers ─────────────────────────────

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private int unrevokedTokens(UUID userId) {
        return count("select count(*) from refresh_tokens where user_id = ? and revoked_at is null", userId);
    }

    private int liveTokens(UUID userId) {
        return count("select count(*) from refresh_tokens where user_id = ? and revoked_at is null and rotated_at is null",
                userId);
    }
}
