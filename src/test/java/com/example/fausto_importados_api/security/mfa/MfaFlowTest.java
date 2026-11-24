package com.example.fausto_importados_api.security.mfa;

import com.example.fausto_importados_api.model.MfaRecoveryCode;
import com.example.fausto_importados_api.model.User;
import com.example.fausto_importados_api.model.enums.Role;
import com.example.fausto_importados_api.repository.AuthThrottleRepository;
import com.example.fausto_importados_api.repository.MfaRecoveryCodeRepository;
import com.example.fausto_importados_api.repository.RefreshTokenRepository;
import com.example.fausto_importados_api.repository.UserRepository;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Login em dois passos (senha + TOTP), cadastro do 2FA, códigos de recuperação e bloqueio progressivo,
 * contra o contexto real do Spring + H2. Aqui o 2FA está LIGADO (nos demais testes de sessão, desligado).
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:mfaflow;DB_CLOSE_DELAY=-1",
        "security.auth.mfa-required=true"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MfaFlowTest {

    private static final Instant T0 = Instant.parse("2026-03-01T12:00:00Z");
    private static final String PASSWORD = "senha-longa-de-teste";

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
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired TotpService totp;
    @Autowired ObjectMapper json;

    @BeforeEach
    void reset() {
        clock.set(T0);
        tokens.deleteAll();
        throttles.deleteAll();
    }

    // ───────────────────────────── cadastro do 2FA ─────────────────────────────

    @Test
    void anAdminWithoutMfa_getsASetupChallenge_andNoSessionYet() throws Exception {
        String email = newAdmin().getEmail();

        MvcResult res = login(email, PASSWORD);

        JsonNode body = body(res);
        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        assertThat(body.get("status").asText()).isEqualTo("MFA_SETUP_REQUIRED");
        assertThat(body.get("challenge").asText()).isNotBlank();
        assertThat(body.has("accessToken")).isFalse();
        assertThat(res.getResponse().getHeaders("Set-Cookie")).isEmpty(); // nada de refresh token
        assertThat(tokens.count()).isZero();                                // nem sessão
    }

    @Test
    void theChallengeIsNotAnAccessToken() throws Exception {
        String challenge = body(login(newAdmin().getEmail(), PASSWORD)).get("challenge").asText();

        assertThat(status(get("/auth/me").header("Authorization", "Bearer " + challenge))).isEqualTo(401);
        assertThat(status(get("/api/orders/report").header("Authorization", "Bearer " + challenge))).isEqualTo(401);
    }

    @Test
    void theChallengeAsABearerIsRejectedCleanlyEverywhere_includingLogout() throws Exception {
        String challenge = body(login(newAdmin().getEmail(), PASSWORD)).get("challenge").asText();

        // o logout aceita token vencido, então lê as claims à mão: com um desafio não pode dar erro 500
        int logout = status(post("/auth/logout").header("Authorization", "Bearer " + challenge));
        assertThat(logout).isEqualTo(200);
        assertThat(tokens.count()).isZero(); // e não revogou sessão nenhuma
    }

    @Test
    void anAccessTokenIsNotAValidChallenge() throws Exception {
        User user = newUser(Role.USER);
        String access = body(login(user.getEmail(), PASSWORD)).get("accessToken").asText();
        User admin = newAdmin();

        assertThat(send("/auth/mfa/setup", Map.of("challenge", access)).getResponse().getStatus()).isEqualTo(401);
        assertThat(send("/auth/login/mfa", Map.of("challenge", access, "code", "123456")).getResponse().getStatus()).isEqualTo(401);
        assertThat(users.findById(admin.getId()).orElseThrow().getTotpSecretEnc()).isNull();
    }

    @Test
    void enrollment_activatesMfa_keepsTheSecretEncrypted_andOpensTheSession() throws Exception {
        User admin = newAdmin();
        String challenge = body(login(admin.getEmail(), PASSWORD)).get("challenge").asText();

        // 1) início: segredo e URI para o QR code
        JsonNode setup = body(send("/auth/mfa/setup", Map.of("challenge", challenge)));
        String secretText = setup.get("secret").asText();
        assertThat(secretText).matches("[A-Z2-7]{32}");
        assertThat(setup.get("otpauthUri").asText()).startsWith("otpauth://totp/").contains("secret=" + secretText);

        User pending = users.findById(admin.getId()).orElseThrow();
        assertThat(pending.isTotpEnabled()).isFalse(); // ainda pendente
        assertThat(pending.getTotpSecretEnc()).startsWith("v1:").doesNotContain(secretText);

        // 2) código errado não ativa nada
        MvcResult wrong = send("/auth/mfa/setup/confirm", Map.of("challenge", challenge, "code", "000000"));
        assertThat(wrong.getResponse().getStatus()).isEqualTo(401);
        assertThat(users.findById(admin.getId()).orElseThrow().isTotpEnabled()).isFalse();
        assertThat(tokens.count()).isZero();

        // 3) código certo: ativa, devolve códigos de recuperação e abre a sessão
        byte[] secret = new Base32().decode(secretText);
        String code = totp.codeAt(secret, clock.instant());
        MvcResult done = send("/auth/mfa/setup/confirm", Map.of("challenge", challenge, "code", code));

        assertThat(done.getResponse().getStatus()).isEqualTo(200);
        JsonNode confirmed = body(done);
        assertThat(confirmed.get("status").asText()).isEqualTo("AUTHENTICATED");
        assertThat(confirmed.get("recoveryCodes")).hasSize(10);
        confirmed.get("recoveryCodes").forEach(c -> assertThat(c.asText()).matches("[A-Z2-9]{4}(-[A-Z2-9]{4}){3}"));
        assertThat(done.getResponse().getHeaders("Set-Cookie")).anyMatch(h -> h.startsWith("refresh_token="));

        User enabled = users.findById(admin.getId()).orElseThrow();
        assertThat(enabled.isTotpEnabled()).isTrue();
        assertThat(enabled.getTotpLastStep()).isEqualTo(totp.currentStep());

        // os códigos de recuperação só existem como hash
        List<MfaRecoveryCode> stored = recoveryRepo.findAll().stream().filter(c -> c.getUserId().equals(admin.getId())).toList();
        assertThat(stored).hasSize(10);
        for (JsonNode shown : confirmed.get("recoveryCodes")) {
            String plain = shown.asText().replace("-", "");
            assertThat(stored).noneMatch(c -> c.getCodeHash().equals(plain) || c.getCodeHash().equals(shown.asText()));
        }

        JsonNode me = body(mvc.perform(get("/auth/me").header("Authorization", "Bearer " + confirmed.get("accessToken").asText())).andReturn());
        assertThat(me.get("role").asText()).isEqualTo("ADMIN");
        assertThat(me.get("email").asText()).isEqualTo(admin.getEmail());
    }

    @Test
    void setupIsRefusedOnceMfaIsAlreadyEnabled() throws Exception {
        User admin = newAdmin();
        enroll(admin.getEmail());
        clock.advance(Duration.ofSeconds(30));

        String challenge = body(login(admin.getEmail(), PASSWORD)).get("challenge").asText(); // agora é MFA_REQUIRED

        // quem só sabe a senha não consegue trocar o 2FA de uma conta que já o tem
        assertThat(send("/auth/mfa/setup", Map.of("challenge", challenge)).getResponse().getStatus()).isEqualTo(401);
        assertThat(send("/auth/mfa/setup/confirm", Map.of("challenge", challenge, "code", "123456")).getResponse().getStatus()).isEqualTo(401);
    }

    // ───────────────────────────── login com 2FA ─────────────────────────────

    @Test
    void afterEnrollment_loginNeedsTheSecondFactor() throws Exception {
        User admin = newAdmin();
        Enrolled enrolled = enroll(admin.getEmail());
        clock.advance(Duration.ofSeconds(30)); // o código do cadastro já foi usado neste passo

        MvcResult first = login(admin.getEmail(), PASSWORD);
        JsonNode body = body(first);
        assertThat(body.get("status").asText()).isEqualTo("MFA_REQUIRED");
        assertThat(body.has("accessToken")).isFalse();
        assertThat(first.getResponse().getHeaders("Set-Cookie")).isEmpty();

        MvcResult wrong = send("/auth/login/mfa", Map.of("challenge", body.get("challenge").asText(), "code", "000000"));
        assertThat(wrong.getResponse().getStatus()).isEqualTo(401);
        assertThat(wrong.getResponse().getHeaders("Set-Cookie")).isEmpty();

        MvcResult ok = send("/auth/login/mfa", Map.of(
                "challenge", body.get("challenge").asText(), "code", currentCode(enrolled)));
        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(ok).get("status").asText()).isEqualTo("AUTHENTICATED");
        assertThat(ok.getResponse().getHeaders("Set-Cookie")).anyMatch(h -> h.startsWith("refresh_token="));
    }

    @Test
    void aTotpCodeCanOnlyBeUsedOnce() throws Exception {
        User admin = newAdmin();
        Enrolled enrolled = enroll(admin.getEmail());
        String usedAtEnrollment = totp.codeAt(enrolled.secret(), clock.instant());

        // o mesmo código do cadastro, no mesmo passo de 30 s: replay
        String challenge = body(login(admin.getEmail(), PASSWORD)).get("challenge").asText();
        assertThat(send("/auth/login/mfa", Map.of("challenge", challenge, "code", usedAtEnrollment))
                .getResponse().getStatus()).isEqualTo(401);

        // passo seguinte: o código novo vale uma vez...
        clock.advance(Duration.ofSeconds(30));
        String fresh = currentCode(enrolled);
        assertThat(send("/auth/login/mfa", Map.of("challenge", challenge, "code", fresh))
                .getResponse().getStatus()).isEqualTo(200);
        // ...e não vale de novo
        assertThat(send("/auth/login/mfa", Map.of("challenge", challenge, "code", fresh))
                .getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void theSameCodeSentTwiceAtTheSameTime_succeedsOnlyOnce() throws Exception {
        User admin = newAdmin();
        Enrolled enrolled = enroll(admin.getEmail());
        clock.advance(Duration.ofSeconds(30));
        String challenge = body(login(admin.getEmail(), PASSWORD)).get("challenge").asText();
        String code = currentCode(enrolled);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch go = new CountDownLatch(1);
            java.util.concurrent.Callable<Integer> attempt = () -> {
                go.await();
                return send("/auth/login/mfa", Map.of("challenge", challenge, "code", code)).getResponse().getStatus();
            };
            Future<Integer> a = pool.submit(attempt);
            Future<Integer> b = pool.submit(attempt);
            go.countDown();

            assertThat(List.of(a.get(), b.get())).containsExactlyInAnyOrder(200, 401);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aRecoveryCodeWorksOnce_inAnyCaseAndFormat() throws Exception {
        User admin = newAdmin();
        Enrolled enrolled = enroll(admin.getEmail());
        String code = enrolled.recoveryCodes().get(0);

        String challenge1 = body(login(admin.getEmail(), PASSWORD)).get("challenge").asText();
        assertThat(send("/auth/login/mfa", Map.of("challenge", challenge1, "code", code)).getResponse().getStatus()).isEqualTo(200);

        String challenge2 = body(login(admin.getEmail(), PASSWORD)).get("challenge").asText();
        assertThat(send("/auth/login/mfa", Map.of("challenge", challenge2, "code", code)).getResponse().getStatus()).isEqualTo(401);

        // outro código, digitado em minúsculas e sem hífens
        String other = enrolled.recoveryCodes().get(1).toLowerCase().replace("-", "");
        assertThat(send("/auth/login/mfa", Map.of("challenge", challenge2, "code", other)).getResponse().getStatus()).isEqualTo(200);
        assertThat(recoveryRepo.countByUserIdAndUsedAtIsNull(admin.getId())).isEqualTo(8);
    }

    @Test
    void theChallengeExpires() throws Exception {
        User admin = newAdmin();
        Enrolled enrolled = enroll(admin.getEmail());
        clock.advance(Duration.ofSeconds(30));
        String challenge = body(login(admin.getEmail(), PASSWORD)).get("challenge").asText();

        clock.advance(Duration.ofMinutes(5).plusSeconds(1));

        assertThat(send("/auth/login/mfa", Map.of("challenge", challenge, "code", currentCode(enrolled)))
                .getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void aDeactivatedAdminCannotFinishALoginAlreadyInProgress() throws Exception {
        User admin = newAdmin();
        Enrolled enrolled = enroll(admin.getEmail());
        clock.advance(Duration.ofSeconds(30));
        String challenge = body(login(admin.getEmail(), PASSWORD)).get("challenge").asText();

        User current = users.findById(admin.getId()).orElseThrow();
        current.setActive(false);
        users.save(current);

        assertThat(send("/auth/login/mfa", Map.of("challenge", challenge, "code", currentCode(enrolled)))
                .getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void aRegularUserLogsInDirectly_evenWithMfaRequiredForAdmins() throws Exception {
        User user = newUser(Role.USER);

        MvcResult res = login(user.getEmail(), PASSWORD);

        JsonNode body = body(res);
        assertThat(body.get("status").asText()).isEqualTo("AUTHENTICATED");
        JsonNode me = body(mvc.perform(get("/auth/me").header("Authorization", "Bearer " + body.get("accessToken").asText())).andReturn());
        assertThat(me.get("role").asText()).isEqualTo("USER");
    }

    // ───────────────────────────── senha temporária e prazo ─────────────────────────────

    @Test
    void aTemporaryPasswordMustBeReplacedWhenEnrolling() throws Exception {
        User admin = newAdmin();
        admin.setMustChangePassword(true);
        users.save(admin);

        JsonNode loginBody = body(login(admin.getEmail(), PASSWORD));
        assertThat(loginBody.get("status").asText()).isEqualTo("MFA_SETUP_REQUIRED");
        assertThat(loginBody.get("passwordChangeRequired").asBoolean()).isTrue();
        String challenge = loginBody.get("challenge").asText();
        byte[] secret = new Base32().decode(body(send("/auth/mfa/setup", Map.of("challenge", challenge))).get("secret").asText());
        String code = totp.codeAt(secret, clock.instant());

        // sem senha nova, ou com senha fraca: recusado e nada é ativado
        assertThat(send("/auth/mfa/setup/confirm", Map.of("challenge", challenge, "code", code)).getResponse().getStatus()).isEqualTo(400);
        assertThat(send("/auth/mfa/setup/confirm", Map.of("challenge", challenge, "code", code, "newPassword", "curta")).getResponse().getStatus()).isEqualTo(400);
        assertThat(users.findById(admin.getId()).orElseThrow().isTotpEnabled()).isFalse();

        String newPassword = "outra-senha-bem-longa";
        MvcResult done = send("/auth/mfa/setup/confirm", Map.of("challenge", challenge, "code", code, "newPassword", newPassword));
        assertThat(done.getResponse().getStatus()).isEqualTo(200);

        User updated = users.findById(admin.getId()).orElseThrow();
        assertThat(updated.isMustChangePassword()).isFalse();
        assertThat(updated.isTotpEnabled()).isTrue();
        assertThat(login(admin.getEmail(), PASSWORD).getResponse().getStatus()).isEqualTo(401); // a temporária morreu
        assertThat(body(login(admin.getEmail(), newPassword)).get("status").asText()).isEqualTo("MFA_REQUIRED");
    }

    @Test
    void anExpiredEnrollmentWindow_looksLikeAWrongPassword() throws Exception {
        User admin = newAdmin();
        admin.setEnrollmentExpiresAt(T0.plus(Duration.ofHours(24)));
        users.save(admin);

        assertThat(body(login(admin.getEmail(), PASSWORD)).get("status").asText()).isEqualTo("MFA_SETUP_REQUIRED");

        clock.advance(Duration.ofHours(25));
        MvcResult expired = login(admin.getEmail(), PASSWORD);
        MvcResult wrongPassword = login(admin.getEmail(), "senha-errada-qualquer");

        assertThat(expired.getResponse().getStatus()).isEqualTo(401);
        assertThat(body(expired).get("message").asText()).isEqualTo(body(wrongPassword).get("message").asText());
    }

    // ───────────────────────────── força bruta ─────────────────────────────

    @Test
    void fiveWrongPasswordsLockTheAccount_evenForTheRightPassword_untilTheLockEnds() throws Exception {
        User admin = newAdmin();
        for (int i = 0; i < 5; i++) {
            assertThat(login(admin.getEmail(), "errada-" + i).getResponse().getStatus()).isEqualTo(401);
        }

        MvcResult locked = login(admin.getEmail(), PASSWORD); // a senha CERTA também é recusada durante o bloqueio
        assertThat(locked.getResponse().getStatus()).isEqualTo(429);
        assertThat(locked.getResponse().getHeader("Retry-After")).isEqualTo("60");

        clock.advance(Duration.ofSeconds(59));
        assertThat(login(admin.getEmail(), PASSWORD).getResponse().getStatus()).isEqualTo(429);

        clock.advance(Duration.ofSeconds(2));
        assertThat(login(admin.getEmail(), PASSWORD).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void theLockGrowsWithEachNewFailure_andIsCapped() throws Exception {
        User admin = newAdmin();
        for (int i = 0; i < 5; i++) login(admin.getEmail(), "errada");   // 5ª falha → 1 min
        clock.advance(Duration.ofSeconds(61));
        login(admin.getEmail(), "errada");                               // 6ª falha → 2 min

        MvcResult second = login(admin.getEmail(), PASSWORD);
        assertThat(second.getResponse().getStatus()).isEqualTo(429);
        assertThat(second.getResponse().getHeader("Retry-After")).isEqualTo("120");

        // muitas falhas depois, o bloqueio nunca passa de 30 min
        for (int i = 0; i < 12; i++) {
            clock.advance(Duration.ofMinutes(31));
            login(admin.getEmail(), "errada");
        }
        MvcResult capped = login(admin.getEmail(), PASSWORD);
        assertThat(capped.getResponse().getStatus()).isEqualTo(429);
        assertThat(Integer.parseInt(capped.getResponse().getHeader("Retry-After"))).isLessThanOrEqualTo(1800);
    }

    @Test
    void anUnknownEmailIsLockedLikeARealOne_soTheLockRevealsNothing() throws Exception {
        String ghost = "ninguem-" + UUID.randomUUID() + "@test.com";
        for (int i = 0; i < 5; i++) {
            assertThat(login(ghost, "qualquer-coisa").getResponse().getStatus()).isEqualTo(401);
        }

        assertThat(login(ghost, "qualquer-coisa").getResponse().getStatus()).isEqualTo(429);
    }

    @Test
    void emailCaseDoesNotAllowSkippingTheLock() throws Exception {
        User admin = newAdmin();
        for (int i = 0; i < 5; i++) login(admin.getEmail().toUpperCase(), "errada");

        assertThat(login(admin.getEmail(), PASSWORD).getResponse().getStatus()).isEqualTo(429);
    }

    @Test
    void aSuccessfulLoginResetsTheAccountCounter() throws Exception {
        User user = newUser(Role.USER);
        for (int i = 0; i < 4; i++) login(user.getEmail(), "errada");
        assertThat(login(user.getEmail(), PASSWORD).getResponse().getStatus()).isEqualTo(200);

        // sem o reset, a 5ª falha somada às 4 anteriores travaria a conta
        for (int i = 0; i < 4; i++) {
            assertThat(login(user.getEmail(), "errada").getResponse().getStatus()).isEqualTo(401);
        }
    }

    @Test
    void wrongSecondFactorCodesCountTowardTheSameLock() throws Exception {
        User admin = newAdmin();
        Enrolled enrolled = enroll(admin.getEmail());
        clock.advance(Duration.ofSeconds(30));
        String challenge = body(login(admin.getEmail(), PASSWORD)).get("challenge").asText();

        for (int i = 0; i < 5; i++) {
            assertThat(send("/auth/login/mfa", Map.of("challenge", challenge, "code", "000000")).getResponse().getStatus()).isEqualTo(401);
        }

        // com a conta travada nem o código CERTO passa
        assertThat(send("/auth/login/mfa", Map.of("challenge", challenge, "code", currentCode(enrolled)))
                .getResponse().getStatus()).isEqualTo(429);
        assertThat(login(admin.getEmail(), PASSWORD).getResponse().getStatus()).isEqualTo(429);
    }

    @Test
    void manyFailuresFromOneIpLockThatIpEvenForAccountsNeverTried() throws Exception {
        for (int i = 0; i < 20; i++) {
            login("alvo-" + i + "@test.com", "errada");
        }

        assertThat(login(newUser(Role.USER).getEmail(), PASSWORD).getResponse().getStatus()).isEqualTo(429);
    }

    @Test
    void theIpIsTheLastForwardedHop_soAForgedFirstEntryDoesNotEvadeTheLimit() throws Exception {
        // o cliente forja o primeiro item a cada tentativa; o último é o que o proxy confiável acrescentou
        for (int i = 0; i < 20; i++) {
            mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                    .header("X-Forwarded-For", "10.0.0." + i + ", 203.0.113.9")
                    .content(json.writeValueAsString(Map.of("email", "alvo-" + i + "@test.com", "password", "errada")))).andReturn();
        }

        MvcResult res = mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .header("X-Forwarded-For", "10.9.9.9, 203.0.113.9")
                .content(json.writeValueAsString(Map.of("email", newUser(Role.USER).getEmail(), "password", PASSWORD)))).andReturn();

        assertThat(res.getResponse().getStatus()).isEqualTo(429);
    }

    // ───────────────────────────── helpers ─────────────────────────────

    private record Enrolled(byte[] secret, List<String> recoveryCodes) {
    }

    /** Faz o cadastro completo do 2FA (senha → setup → confirmação) usando o passo de tempo atual. */
    private Enrolled enroll(String email) throws Exception {
        String challenge = body(login(email, PASSWORD)).get("challenge").asText();
        JsonNode setup = body(send("/auth/mfa/setup", Map.of("challenge", challenge)));
        byte[] secret = new Base32().decode(setup.get("secret").asText());
        MvcResult done = send("/auth/mfa/setup/confirm", Map.of(
                "challenge", challenge, "code", totp.codeAt(secret, clock.instant())));
        assertThat(done.getResponse().getStatus()).isEqualTo(200);
        List<String> codes = new java.util.ArrayList<>();
        body(done).get("recoveryCodes").forEach(c -> codes.add(c.asText()));
        return new Enrolled(secret, codes);
    }

    private String currentCode(Enrolled enrolled) {
        return totp.codeAt(enrolled.secret(), clock.instant());
    }

    private User newAdmin() {
        return newUser(Role.ADMIN);
    }

    private User newUser(Role role) {
        User u = new User();
        u.setEmail("u-" + UUID.randomUUID() + "@test.com");
        u.setPassword(passwordEncoder.encode(PASSWORD));
        u.setRole(role);
        u.setActive(true);
        return users.save(u);
    }

    private MvcResult login(String email, String password) throws Exception {
        return send("/auth/login", Map.of("email", email, "password", password));
    }

    private MvcResult send(String path, Map<String, String> payload) throws Exception {
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(payload))).andReturn();
    }

    private JsonNode body(MvcResult res) throws Exception {
        return json.readTree(res.getResponse().getContentAsString());
    }

    private int status(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request).andReturn().getResponse().getStatus();
    }
}
