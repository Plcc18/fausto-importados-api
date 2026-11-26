package com.example.fausto_importados_api.security;

import com.example.fausto_importados_api.audit.AuditAction;
import com.example.fausto_importados_api.model.AuditLog;
import com.example.fausto_importados_api.model.Product;
import com.example.fausto_importados_api.model.User;
import com.example.fausto_importados_api.model.enums.Role;
import com.example.fausto_importados_api.repository.AuditLogRepository;
import com.example.fausto_importados_api.repository.AuthThrottleRepository;
import com.example.fausto_importados_api.repository.MfaRecoveryCodeRepository;
import com.example.fausto_importados_api.repository.ProductRepository;
import com.example.fausto_importados_api.repository.RefreshTokenRepository;
import com.example.fausto_importados_api.repository.UserRepository;
import com.example.fausto_importados_api.security.mfa.TotpService;
import com.example.fausto_importados_api.services.AdminAccountService;
import com.example.fausto_importados_api.services.exception.AccountConflictException;
import com.example.fausto_importados_api.support.MutableClock;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.codec.binary.Base32;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Gestão de administradores, reautenticação por 2FA e trilha de auditoria — com o 2FA LIGADO e o relógio sob
 * controle do teste. Cada passo que consome um código TOTP avança o relógio em 30 s (um código vale uma vez).
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:adminaccount;DB_CLOSE_DELAY=-1",
        "security.auth.mfa-required=true"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminAccountTest {

    private static final Instant T0 = Instant.parse("2026-03-01T12:00:00Z");
    private static final String PASSWORD = "senha-longa-de-teste";
    private static final String USER_AGENT = "JUnit-AdminAccountTest/1.0";

    @TestConfiguration
    static class TestClockConfig {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(T0);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired MutableClock clock;
    @Autowired UserRepository users;
    @Autowired RefreshTokenRepository tokens;
    @Autowired AuthThrottleRepository throttles;
    @Autowired MfaRecoveryCodeRepository recoveryRepo;
    @Autowired AuditLogRepository auditRepo;
    @Autowired ProductRepository products;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired TotpService totp;
    @Autowired AdminAccountService accounts;
    @Autowired ObjectMapper json;

    @BeforeEach
    void reset() {
        clock.set(T0);
        throttles.deleteAll();
    }

    // ───────────────────────────── reautenticação ─────────────────────────────

    @Test
    void aSensitiveOperationNeedsAFreshReauth_andACreatedAdminGetsATemporaryPassword() throws Exception {
        Session a = signIn(enroll(newAdmin()));

        // sem a prova de reautenticação: 403 com código próprio, e nada é criado
        MvcResult denied = call(post("/api/user"), a, null, Map.of("email", "novo@test.com"));
        assertThat(denied.getResponse().getStatus()).isEqualTo(403);
        assertThat(body(denied).get("code").asText()).isEqualTo("reauth_required");
        assertThat(users.existsByEmail("novo@test.com")).isFalse();

        String reauth = reauth(a);
        MvcResult created = call(post("/api/user"), a, reauth, Map.of("email", "Novo@Test.com"));

        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        JsonNode result = body(created);
        String temporaryPassword = result.get("temporaryPassword").asText();
        assertThat(temporaryPassword).hasSize(20).matches("[A-Za-z2-9]+");
        assertThat(Instant.parse(result.get("enrollmentExpiresAt").asText())).isEqualTo(clock.instant().plus(Duration.ofHours(24)));

        User stored = users.findByEmailIgnoreCase("novo@test.com").orElseThrow();
        assertThat(stored.getEmail()).isEqualTo("novo@test.com"); // normalizado
        assertThat(stored.getRole()).isEqualTo(Role.ADMIN);
        assertThat(stored.isMustChangePassword()).isTrue();
        assertThat(stored.isTotpEnabled()).isFalse();
        assertThat(stored.getPassword()).isNotEqualTo(temporaryPassword);
        assertThat(passwordEncoder.matches(temporaryPassword, stored.getPassword())).isTrue();

        // a mesma prova ainda vale dentro do prazo; e-mail repetido (qualquer caixa) é conflito
        assertThat(call(post("/api/user"), a, reauth, Map.of("email", "NOVO@test.com")).getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void theReauthProofIsBoundToTheSessionTheUserAndTheClock() throws Exception {
        Enrolled who = enroll(newAdmin());
        Session first = signIn(who);
        Session second = signIn(who); // outra sessão do MESMO administrador
        Session stranger = signIn(enroll(newAdmin()));
        String proof = reauth(first);
        Map<String, String> payload = Map.of("email", "alguem-" + UUID.randomUUID() + "@test.com");

        assertThat(call(post("/api/user"), first, proof, payload).getResponse().getStatus()).isEqualTo(201);
        assertThat(call(post("/api/user"), second, proof, payload).getResponse().getStatus()).isEqualTo(403);   // outra sessão
        assertThat(call(post("/api/user"), stranger, proof, payload).getResponse().getStatus()).isEqualTo(403); // outra pessoa
        assertThat(call(post("/api/user"), first, first.access(), payload).getResponse().getStatus()).isEqualTo(403); // access token não é prova
        assertThat(call(post("/api/user"), first, "lixo", payload).getResponse().getStatus()).isEqualTo(403);

        // a prova também não serve como access token
        assertThat(mvc.perform(get("/auth/me").header("Authorization", "Bearer " + proof)).andReturn().getResponse().getStatus()).isEqualTo(401);

        clock.advance(Duration.ofMinutes(5).plusSeconds(1)); // vence
        assertThat(call(post("/api/user"), first, proof, payload).getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void wrongReauthCodesAreRefusedWith403_andLockTheAccountLikeLoginDoes() throws Exception {
        Session a = signIn(enroll(newAdmin()));
        clock.advance(Duration.ofSeconds(30));

        for (int i = 0; i < 5; i++) {
            MvcResult wrong = call(post("/auth/reauth"), a, null, Map.of("code", "000000"));
            assertThat(wrong.getResponse().getStatus()).isEqualTo(403); // 403, não 401: o front não deve "renovar e repetir"
            assertThat(body(wrong).get("code").asText()).isEqualTo("invalid_code");
            assertThat(body(wrong).has("reauthToken")).isFalse();
        }
        // travada: nem o código certo passa
        MvcResult locked = call(post("/auth/reauth"), a, null, Map.of("code", code(a.who())));
        assertThat(locked.getResponse().getStatus()).isEqualTo(429);
        assertThat(locked.getResponse().getHeader("Retry-After")).isNotBlank();
    }

    // ───────────────────────────── ciclo de vida das contas ─────────────────────────────

    @Test
    void aNewAdmin_activatesWithAPermanentPassword_andTheTemporaryOneDies() throws Exception {
        Session a = signIn(enroll(newAdmin()));
        MvcResult created = call(post("/api/user"), a, reauth(a), Map.of("email", "b-" + UUID.randomUUID() + "@test.com"));
        String email = body(created).get("email").asText();
        String temporary = body(created).get("temporaryPassword").asText();

        JsonNode login = body(send("/auth/login", Map.of("email", email, "password", temporary)));
        assertThat(login.get("status").asText()).isEqualTo("MFA_SETUP_REQUIRED");
        assertThat(login.get("passwordChangeRequired").asBoolean()).isTrue();
        String challenge = login.get("challenge").asText();
        byte[] secret = new Base32().decode(body(send("/auth/mfa/setup", Map.of("challenge", challenge))).get("secret").asText());

        String permanent = "minha-senha-definitiva-1";
        clock.advance(Duration.ofSeconds(30));
        MvcResult done = send("/auth/mfa/setup/confirm", Map.of(
                "challenge", challenge, "code", totp.codeAt(secret, clock.instant()), "newPassword", permanent));
        assertThat(done.getResponse().getStatus()).isEqualTo(200);
        String bAccess = body(done).get("accessToken").asText();

        assertThat(mvc.perform(get("/api/user").header("Authorization", "Bearer " + bAccess)).andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(send("/auth/login", Map.of("email", email, "password", temporary)).getResponse().getStatus()).isEqualTo(401);
        assertThat(body(send("/auth/login", Map.of("email", email, "password", permanent))).get("status").asText()).isEqualTo("MFA_REQUIRED");
    }

    @Test
    void deactivatingAnAdmin_cutsEverySessionImmediately_andBlocksLogin_untilReactivated() throws Exception {
        Session a = signIn(enroll(newAdmin()));
        Enrolled bWho = enroll(newAdmin());
        Session b = signIn(bWho);
        assertThat(callGet("/api/user", b).getResponse().getStatus()).isEqualTo(200);

        String proof = reauth(a);
        MvcResult off = call(patch("/api/user/" + bWho.user().getId() + "/active"), a, proof, Map.of("active", false));
        assertThat(off.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(off).get("active").asBoolean()).isFalse();

        assertThat(callGet("/api/user", b).getResponse().getStatus()).isEqualTo(401);            // access token já vigente
        assertThat(refresh(b.refreshCookie()).getResponse().getStatus()).isEqualTo(401);          // e a renovação
        assertThat(send("/auth/login", Map.of("email", bWho.user().getEmail(), "password", PASSWORD))
                .getResponse().getStatus()).isEqualTo(401);                                       // e o login

        MvcResult on = call(patch("/api/user/" + bWho.user().getId() + "/active"), a, proof, Map.of("active", true));
        assertThat(on.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(send("/auth/login", Map.of("email", bWho.user().getEmail(), "password", PASSWORD)))
                .get("status").asText()).isEqualTo("MFA_REQUIRED");
    }

    @Test
    void youCannotDeactivateDeleteOrResetYourOwnAccount() throws Exception {
        Session a = signIn(enroll(newAdmin()));
        String proof = reauth(a);
        String self = a.who().user().getId().toString();

        assertThat(call(patch("/api/user/" + self + "/active"), a, proof, Map.of("active", false)).getResponse().getStatus()).isEqualTo(409);
        assertThat(call(delete("/api/user/" + self), a, proof, null).getResponse().getStatus()).isEqualTo(409);
        assertThat(call(post("/api/user/" + self + "/reset-credentials"), a, proof, null).getResponse().getStatus()).isEqualTo(409);
        assertThat(users.findById(a.who().user().getId()).orElseThrow().getActive()).isTrue();
    }

    @Test
    void theLastActiveAdminCanNeverBeDeactivatedOrDeleted() {
        User only = newAdmin();
        // deixa `only` como único administrador ativo
        users.findAll().stream().filter(u -> u.getRole() == Role.ADMIN && !u.getId().equals(only.getId()))
                .forEach(u -> {
                    u.setActive(false);
                    users.save(u);
                });
        assertThat(users.countByRoleAndActiveTrue(Role.ADMIN)).isEqualTo(1);

        assertThatThrownBy(() -> accounts.setActive(only.getId(), false, null))
                .isInstanceOf(AccountConflictException.class).hasMessageContaining("last active administrator");
        assertThatThrownBy(() -> accounts.delete(only.getId()))
                .isInstanceOf(AccountConflictException.class).hasMessageContaining("last active administrator");
        assertThat(users.findById(only.getId())).isPresent();
    }

    @Test
    void resettingCredentials_wipesTheSecondFactor_endsSessions_unlocksTheAccount_andStartsANewEnrollment() throws Exception {
        Session a = signIn(enroll(newAdmin()));
        Enrolled bWho = enroll(newAdmin());
        Session b = signIn(bWho);
        for (int i = 0; i < 5; i++) send("/auth/login", Map.of("email", bWho.user().getEmail(), "password", "errada")); // trava B
        assertThat(send("/auth/login", Map.of("email", bWho.user().getEmail(), "password", PASSWORD)).getResponse().getStatus()).isEqualTo(429);

        MvcResult reset = call(post("/api/user/" + bWho.user().getId() + "/reset-credentials"), a, reauth(a), null);

        assertThat(reset.getResponse().getStatus()).isEqualTo(200);
        String temporary = body(reset).get("temporaryPassword").asText();
        User stored = users.findById(bWho.user().getId()).orElseThrow();
        assertThat(stored.isTotpEnabled()).isFalse();
        assertThat(stored.getTotpSecretEnc()).isNull();
        assertThat(stored.getTotpLastStep()).isNull();
        assertThat(stored.isMustChangePassword()).isTrue();
        assertThat(stored.getEnrollmentExpiresAt()).isEqualTo(clock.instant().plus(Duration.ofHours(24)));
        assertThat(recoveryRepo.countByUserIdAndUsedAtIsNull(bWho.user().getId())).isZero();

        assertThat(callGet("/api/user", b).getResponse().getStatus()).isEqualTo(401);
        assertThat(refresh(b.refreshCookie()).getResponse().getStatus()).isEqualTo(401);
        assertThat(send("/auth/login", Map.of("email", bWho.user().getEmail(), "password", PASSWORD)).getResponse().getStatus()).isEqualTo(401);
        // destravada, e a senha temporária leva ao cadastro do 2FA
        assertThat(body(send("/auth/login", Map.of("email", bWho.user().getEmail(), "password", temporary)))
                .get("status").asText()).isEqualTo("MFA_SETUP_REQUIRED");
    }

    @Test
    void changingAnEmail_isCaseInsensitive_rejectsDuplicates_andIsAudited() throws Exception {
        Session a = signIn(enroll(newAdmin()));
        Enrolled bWho = enroll(newAdmin());
        User other = newAdmin();
        String proof = reauth(a);
        String newEmail = "Troca." + UUID.randomUUID() + "@Test.com";

        assertThat(call(put("/api/user/" + bWho.user().getId()), a, proof, Map.of("email", other.getEmail().toUpperCase()))
                .getResponse().getStatus()).isEqualTo(409);
        assertThat(call(put("/api/user/" + bWho.user().getId()), a, proof, Map.of("email", "isso-nao-e-email"))
                .getResponse().getStatus()).isEqualTo(400);
        assertThat(call(put("/api/user/" + bWho.user().getId()), a, proof, Map.of("email", newEmail)).getResponse().getStatus()).isEqualTo(200);

        assertThat(users.findById(bWho.user().getId()).orElseThrow().getEmail()).isEqualTo(newEmail.toLowerCase());
        assertThat(body(send("/auth/login", Map.of("email", newEmail.toUpperCase(), "password", PASSWORD)))
                .get("status").asText()).isEqualTo("MFA_REQUIRED"); // e o login não diferencia a caixa

        AuditLog entry = auditFor(AuditAction.ADMIN_EMAIL_CHANGED, bWho.user().getId().toString()).get(0);
        assertThat(entry.getActorEmail()).isEqualTo(a.who().user().getEmail());
        assertThat(entry.getDetails()).contains(bWho.user().getEmail()).contains(newEmail.toLowerCase());
    }

    @Test
    void passwordCannotBeSetForAnotherUserThroughTheUserEndpoints() throws Exception {
        Session a = signIn(enroll(newAdmin()));
        Enrolled bWho = enroll(newAdmin());

        // um campo "password" no corpo é simplesmente ignorado: não existe caminho para definir a senha de outro
        MvcResult res = call(put("/api/user/" + bWho.user().getId()), a, reauth(a),
                Map.of("email", bWho.user().getEmail(), "password", "definida-por-outro-admin-1"));

        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        assertThat(send("/auth/login", Map.of("email", bWho.user().getEmail(), "password", "definida-por-outro-admin-1"))
                .getResponse().getStatus()).isEqualTo(401);
        assertThat(send("/auth/login", Map.of("email", bWho.user().getEmail(), "password", PASSWORD)).getResponse().getStatus()).isEqualTo(200);
    }

    // ───────────────────────────── conta própria ─────────────────────────────

    @Test
    void changingYourOwnPassword_needsReauthAndTheCurrentPassword_andEndsTheOtherSessions() throws Exception {
        Enrolled who = enroll(newAdmin());
        Session phone = signIn(who);
        Session laptop = signIn(who);
        String proof = reauth(phone);
        String newPassword = "outra-senha-bem-longa-2";

        assertThat(call(post("/auth/password"), phone, null, passwords(PASSWORD, newPassword)).getResponse().getStatus()).isEqualTo(403); // sem reauth
        MvcResult wrongCurrent = call(post("/auth/password"), phone, proof, passwords("nao-e-a-atual", newPassword));
        assertThat(wrongCurrent.getResponse().getStatus()).isEqualTo(403);
        assertThat(body(wrongCurrent).get("code").asText()).isEqualTo("invalid_password");
        assertThat(call(post("/auth/password"), phone, proof, passwords(PASSWORD, "curta")).getResponse().getStatus()).isEqualTo(400);
        assertThat(call(post("/auth/password"), phone, proof, passwords(PASSWORD, PASSWORD)).getResponse().getStatus()).isEqualTo(400);

        assertThat(call(post("/auth/password"), phone, proof, passwords(PASSWORD, newPassword)).getResponse().getStatus()).isEqualTo(204);

        assertThat(callGet("/auth/me", phone).getResponse().getStatus()).isEqualTo(200);        // a que trocou continua
        assertThat(callGet("/auth/me", laptop).getResponse().getStatus()).isEqualTo(401);       // as outras caem
        assertThat(refresh(laptop.refreshCookie()).getResponse().getStatus()).isEqualTo(401);
        assertThat(send("/auth/login", Map.of("email", who.user().getEmail(), "password", PASSWORD)).getResponse().getStatus()).isEqualTo(401);
        assertThat(body(send("/auth/login", Map.of("email", who.user().getEmail(), "password", newPassword)))
                .get("status").asText()).isEqualTo("MFA_REQUIRED");
    }

    @Test
    void regeneratingRecoveryCodes_replacesTheOldSetForGood() throws Exception {
        Enrolled who = enroll(newAdmin());
        Session s = signIn(who);
        String oldCode = who.recoveryCodes().get(0);

        MvcResult res = call(post("/auth/mfa/recovery-codes"), s, reauth(s), null);

        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        List<String> fresh = new ArrayList<>();
        body(res).get("recoveryCodes").forEach(c -> fresh.add(c.asText()));
        assertThat(fresh).hasSize(10).doesNotContainAnyElementsOf(who.recoveryCodes());
        assertThat(recoveryRepo.countByUserIdAndUsedAtIsNull(who.user().getId())).isEqualTo(10);

        String challenge = body(send("/auth/login", Map.of("email", who.user().getEmail(), "password", PASSWORD))).get("challenge").asText();
        assertThat(send("/auth/login/mfa", Map.of("challenge", challenge, "code", oldCode)).getResponse().getStatus()).isEqualTo(401);
        assertThat(send("/auth/login/mfa", Map.of("challenge", challenge, "code", fresh.get(0))).getResponse().getStatus()).isEqualTo(200);
    }

    // ───────────────────────────── auditoria ─────────────────────────────

    @Test
    void theAuditTrailRecordsWhoWhatAndFromWhere_butNeverASecret() throws Exception {
        Session a = signIn(enroll(newAdmin()));
        String proof = reauth(a);
        String newAdminEmail = "auditado-" + UUID.randomUUID() + "@test.com";
        MvcResult created = call(post("/api/user"), a, proof, Map.of("email", newAdminEmail));
        String temporaryPassword = body(created).get("temporaryPassword").asText();
        String wrongPassword = "senha-errada-secretissima";
        send("/auth/login", Map.of("email", newAdminEmail, "password", wrongPassword));
        String newPassword = "nova-senha-muito-secreta-9";
        call(post("/auth/password"), a, proof, passwords(PASSWORD, newPassword));

        List<AuditLog> all = auditRepo.findAll();
        String everything = all.stream().map(this::flatten).collect(Collectors.joining("\n"));

        // o que deve estar lá
        assertThat(auditFor(AuditAction.ADMIN_CREATED, null)).anySatisfy(row -> {
            assertThat(row.getActorEmail()).isEqualTo(a.who().user().getEmail());
            assertThat(row.getActorId()).isEqualTo(a.who().user().getId());
            assertThat(row.getIp()).isNotBlank();
            assertThat(row.getUserAgent()).isEqualTo(USER_AGENT);
            assertThat(row.getOccurredAt()).isAfterOrEqualTo(T0);
            assertThat(row.getDetails()).contains(newAdminEmail);
        });
        assertThat(all).extracting(AuditLog::getAction).contains(
                "LOGIN_SUCCESS", "MFA_ENABLED", "REAUTH_SUCCESS", "ADMIN_CREATED", "LOGIN_FAILURE", "PASSWORD_CHANGED");

        // o que NUNCA pode estar
        List<String> secrets = new ArrayList<>(List.of(
                temporaryPassword, wrongPassword, newPassword, PASSWORD,
                a.access(), proof, a.refreshCookie(),
                new Base32().encodeToString(a.who().secret()).replace("=", ""),
                code(a.who())));
        secrets.addAll(a.who().recoveryCodes());
        secrets.addAll(a.who().recoveryCodes().stream().map(c -> c.replace("-", "")).toList());
        for (String secret : secrets) {
            assertThat(everything).as("auditoria não pode conter %s…", secret.substring(0, 4)).doesNotContain(secret);
        }
        assertThat(everything).doesNotContainIgnoringCase("password\":\"");
    }

    @Test
    void sensitiveDetailKeysAreRedactedAndLongValuesAreCut() {
        Object cleaned = com.example.fausto_importados_api.audit.AuditService.sanitize(Map.of(
                "password", "hunter2", "accessToken", "abc", "totpSecret", "XYZ", "recoveryCode", "AAAA",
                "nested", Map.of("authorization", "Bearer x", "ok", "visivel"),
                "long", "x".repeat(2000)));

        String text = cleaned.toString();
        assertThat(text).doesNotContain("hunter2", "Bearer x", "XYZ", "AAAA").contains("[redacted]", "visivel");
        assertThat(((Map<?, ?>) cleaned).get("long").toString()).hasSize(500);
    }

    @Test
    void forbiddenAttemptsAreAudited_butCappedPerMinute_andAnonymousNoiseIsNot() throws Exception {
        User user = new User();
        user.setEmail("comum-" + UUID.randomUUID() + "@test.com");
        user.setPassword(passwordEncoder.encode(PASSWORD));
        user.setRole(Role.USER);
        user.setActive(true);
        user = users.save(user);
        String access = body(send("/auth/login", Map.of("email", user.getEmail(), "password", PASSWORD))).get("accessToken").asText();
        long before = auditRepo.count();

        for (int i = 0; i < 25; i++) {
            assertThat(mvc.perform(get("/api/user").header("Authorization", "Bearer " + access)).andReturn().getResponse().getStatus()).isEqualTo(403);
        }
        for (int i = 0; i < 25; i++) {
            assertThat(mvc.perform(get("/api/user")).andReturn().getResponse().getStatus()).isEqualTo(401);
        }

        UUID actor = user.getId();
        List<AuditLog> denied = auditRepo.findAll().stream()
                .filter(r -> "ACCESS_DENIED".equals(r.getAction()) && actor.equals(r.getActorId())).toList();
        assertThat(denied).isNotEmpty().hasSizeLessThanOrEqualTo(10);
        assertThat(denied).allSatisfy(r -> {
            assertThat(r.getOutcome().name()).isEqualTo("DENIED");
            assertThat(r.getEntityId()).isEqualTo("GET /api/user");
        });
        // os 401 anônimos não geram linha nenhuma (só o login e as negações do usuário comum acima)
        assertThat(auditRepo.count() - before).isLessThanOrEqualTo(10);
    }

    @Test
    void productStockAndOrderChangesAreAudited_andBulkResetsNeedReauth() throws Exception {
        Session a = signIn(enroll(newAdmin()));
        String name = "Perfume Auditado " + UUID.randomUUID();

        // produto: criar → alterar (PATCH) → criar pedido público → concluir → cancelar outro → apagar produto
        MvcResult createProduct = call(post("/api/product"), a, null, Map.of(
                "name", name, "brand", "Marca", "description", "desc", "olfactiveFamily", "FLORAL", "category", "UNISSEX",
                "size", "100", "price", 199.90, "image", "http://img/x.png", "inStock", true, "stockQuantity", 5));
        assertThat(createProduct.getResponse().getStatus()).isEqualTo(201);
        Product product = products.findAll().stream().filter(p -> name.equals(p.getName())).findFirst().orElseThrow();
        String productId = product.getId().toString();

        assertThat(call(patch("/api/product/" + productId), a, null, Map.of("price", 149.90)).getResponse().getStatus()).isEqualTo(200);

        String orderId = placeOrder(productId, 2);
        assertThat(call(post("/api/orders/" + orderId + "/complete"), a, null, null).getResponse().getStatus()).isEqualTo(200);
        String cancelledId = placeOrder(productId, 1);
        assertThat(call(post("/api/orders/" + cancelledId + "/cancel"), a, null, null).getResponse().getStatus()).isEqualTo(200);
        assertThat(products.findById(product.getId()).orElseThrow().getStockQuantity()).isEqualTo(3);

        assertThat(call(delete("/api/product/" + productId), a, null, null).getResponse().getStatus()).isEqualTo(200);

        assertThat(auditFor(AuditAction.PRODUCT_CREATED, productId)).singleElement()
                .satisfies(r -> assertThat(r.getDetails()).contains(name).contains("199.9").contains("\"stockQuantity\":5"));
        assertThat(auditFor(AuditAction.PRODUCT_UPDATED, productId)).singleElement()
                .satisfies(r -> assertThat(r.getDetails()).contains("price").contains("149.9"));
        assertThat(auditFor(AuditAction.ORDER_COMPLETED, orderId)).singleElement()
                .satisfies(r -> assertThat(r.getDetails()).contains(productId).contains("\"quantity\":2"));
        assertThat(auditFor(AuditAction.ORDER_CANCELLED, cancelledId)).hasSize(1);
        assertThat(auditFor(AuditAction.PRODUCT_DELETED, productId)).hasSize(1);

        // zerar histórico/relatório/vendas é sensível: sem reauth → 403 e nada é registrado como feito
        for (String path : List.of("/api/orders/clear-history", "/api/orders/reset-sales", "/api/orders/reset-report")) {
            assertThat(call(delete(path), a, null, null).getResponse().getStatus()).as(path).isEqualTo(403);
        }
        assertThat(auditFor(AuditAction.ORDERS_HISTORY_CLEARED, null)).isEmpty();

        String proof = reauth(a);
        assertThat(call(delete("/api/orders/clear-history"), a, proof, null).getResponse().getStatus()).isEqualTo(204);
        assertThat(call(delete("/api/orders/reset-sales"), a, proof, null).getResponse().getStatus()).isEqualTo(204);
        assertThat(call(delete("/api/orders/reset-report"), a, proof, null).getResponse().getStatus()).isEqualTo(204);
        assertThat(auditFor(AuditAction.ORDERS_HISTORY_CLEARED, null)).hasSize(1);
        assertThat(auditFor(AuditAction.SALES_STATS_RESET, null)).hasSize(1);
        assertThat(auditFor(AuditAction.SALES_REPORT_RESET, null)).hasSize(1);
    }

    @Test
    void theAuditTrailCanBeReadByAdminsOnly() throws Exception {
        Session a = signIn(enroll(newAdmin()));

        MvcResult page = callGet("/api/admin/audit?size=5&action=LOGIN_SUCCESS", a);
        assertThat(page.getResponse().getStatus()).isEqualTo(200);
        JsonNode content = body(page).get("content");
        assertThat(content.size()).isBetween(1, 5);
        content.forEach(row -> assertThat(row.get("action").asText()).isEqualTo("LOGIN_SUCCESS"));

        assertThat(mvc.perform(get("/api/admin/audit")).andReturn().getResponse().getStatus()).isEqualTo(401);
    }

    // ───────────────────────────── helpers ─────────────────────────────

    private record Enrolled(User user, byte[] secret, List<String> recoveryCodes) {
    }

    private record Session(Enrolled who, String access, String refreshCookie) {
    }

    private User newAdmin() {
        User u = new User();
        u.setEmail("admin-" + UUID.randomUUID() + "@test.com");
        u.setPassword(passwordEncoder.encode(PASSWORD));
        u.setRole(Role.ADMIN);
        u.setActive(true);
        return users.save(u);
    }

    /** Cadastro completo do 2FA pelo fluxo HTTP real. Consome o código do passo de tempo atual. */
    private Enrolled enroll(User admin) throws Exception {
        String challenge = body(send("/auth/login", Map.of("email", admin.getEmail(), "password", PASSWORD))).get("challenge").asText();
        byte[] secret = new Base32().decode(body(send("/auth/mfa/setup", Map.of("challenge", challenge))).get("secret").asText());
        MvcResult done = send("/auth/mfa/setup/confirm", Map.of("challenge", challenge, "code", totp.codeAt(secret, clock.instant())));
        assertThat(done.getResponse().getStatus()).isEqualTo(200);
        List<String> codes = new ArrayList<>();
        body(done).get("recoveryCodes").forEach(c -> codes.add(c.asText()));
        return new Enrolled(admin, secret, codes);
    }

    /** Login completo (senha + TOTP) num passo de tempo novo. */
    private Session signIn(Enrolled who) throws Exception {
        clock.advance(Duration.ofSeconds(30));
        String challenge = body(send("/auth/login", Map.of("email", who.user().getEmail(), "password", PASSWORD))).get("challenge").asText();
        MvcResult res = send("/auth/login/mfa", Map.of("challenge", challenge, "code", code(who)));
        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        String cookie = res.getResponse().getHeaders("Set-Cookie").stream()
                .filter(h -> h.startsWith("refresh_token=")).findFirst().orElseThrow();
        return new Session(who, body(res).get("accessToken").asText(), cookie.substring("refresh_token=".length(), cookie.indexOf(';')));
    }

    /** Confirma a identidade com um código novo e devolve a prova para o header X-Reauth. */
    private String reauth(Session s) throws Exception {
        clock.advance(Duration.ofSeconds(30));
        MvcResult res = call(post("/auth/reauth"), s, null, Map.of("code", code(s.who())));
        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        return body(res).get("reauthToken").asText();
    }

    private String code(Enrolled who) {
        return totp.codeAt(who.secret(), clock.instant());
    }

    private String placeOrder(String productId, int quantity) throws Exception {
        Map<String, Object> item = Map.of("productId", productId, "productName", "Perfume", "productSize", "100",
                "productCategory", "UNISSEX", "productFamily", "FLORAL", "onSale", false, "quantity", quantity, "unitPrice", 149.90);
        MvcResult res = mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                "customerName", "Cliente", "customerWhatsapp", "11999999999", "paymentMethod", "PIX",
                "total", 149.90 * quantity, "items", List.of(item))))).andReturn();
        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        return body(res).get("id").asText();
    }

    private List<AuditLog> auditFor(AuditAction action, String entityId) {
        return auditRepo.findAll().stream()
                .filter(r -> action.name().equals(r.getAction()))
                .filter(r -> entityId == null || entityId.equals(r.getEntityId()))
                .toList();
    }

    private String flatten(AuditLog r) {
        return String.join("|", String.valueOf(r.getActorEmail()), r.getAction(), String.valueOf(r.getEntityType()),
                String.valueOf(r.getEntityId()), String.valueOf(r.getIp()), String.valueOf(r.getUserAgent()), String.valueOf(r.getDetails()));
    }

    private static Map<String, String> passwords(String current, String next) {
        return Map.of("currentPassword", current, "newPassword", next);
    }

    private MvcResult send(String path, Map<String, ?> payload) throws Exception {
        return mvc.perform(post(path).header("User-Agent", USER_AGENT).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(payload))).andReturn();
    }

    private MvcResult refresh(String refreshToken) throws Exception {
        return mvc.perform(post("/auth/refresh").header("Origin", "http://localhost:5173")
                .header("X-Requested-With", "fausto-web")
                .cookie(new jakarta.servlet.http.Cookie("refresh_token", refreshToken))).andReturn();
    }

    private MvcResult callGet(String path, Session s) throws Exception {
        return mvc.perform(get(path).header("User-Agent", USER_AGENT).header("Authorization", "Bearer " + s.access())).andReturn();
    }

    /** Chamada autenticada com a sessão `s`; `reauth` (opcional) vai no header X-Reauth. */
    private MvcResult call(MockHttpServletRequestBuilder request, Session s, String reauth, Object payload) throws Exception {
        request.header("User-Agent", USER_AGENT).header("Authorization", "Bearer " + s.access());
        if (reauth != null) {
            request.header("X-Reauth", reauth);
        }
        if (payload != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(payload));
        }
        return mvc.perform(request).andReturn();
    }

    private JsonNode body(MvcResult res) throws Exception {
        String text = res.getResponse().getContentAsString();
        return text.isBlank() ? json.createObjectNode() : json.readTree(text);
    }
}
