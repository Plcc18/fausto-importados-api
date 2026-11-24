package com.example.fausto_importados_api.security.refresh;

import com.example.fausto_importados_api.model.RefreshToken;
import com.example.fausto_importados_api.model.User;
import com.example.fausto_importados_api.model.enums.Role;
import com.example.fausto_importados_api.repository.RefreshTokenRepository;
import com.example.fausto_importados_api.repository.UserRepository;
import com.example.fausto_importados_api.support.MutableClock;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Fluxo completo de autenticação (login, refresh deslizante, rotação, reuso, logout) contra o
 * contexto real do Spring + H2. O tempo é controlado por um {@link MutableClock}.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:authflow;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthFlowTest {

    private static final Instant T0 = Instant.parse("2026-03-01T12:00:00Z");
    private static final String ORIGIN = "http://localhost:5173";
    private static final String ADMIN_EMAIL = "admin@test.com";
    private static final String ADMIN_PASSWORD = "test-password";

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
    @Autowired RefreshTokenRepository tokens;
    @Autowired RefreshTokenService refreshService;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired ObjectMapper json;

    @BeforeEach
    void reset() {
        clock.set(T0);
        tokens.deleteAll();
    }

    // ───────────────────────────── login ─────────────────────────────

    @Test
    void login_returnsAccessTokenAndHttpOnlyStrictRefreshCookie_andStoresOnlyTheHash() throws Exception {
        MvcResult res = mvc.perform(post("/auth/login")
                        .header("User-Agent", "JUnit/Agent")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentials(ADMIN_EMAIL, ADMIN_PASSWORD)))
                .andReturn();

        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        assertThat(json.readTree(res.getResponse().getContentAsString()).get("expiresIn").asLong()).isEqualTo(900);
        String access = accessToken(res);
        assertThat(access.split("\\.")).hasSize(3);

        String refreshCookie = setCookie(res, "refresh_token");
        assertThat(refreshCookie).contains("HttpOnly", "Secure", "SameSite=Strict", "Path=/auth/refresh");

        // o refresh token nunca vai no corpo
        String refresh = cookieValue(res);
        assertThat(res.getResponse().getContentAsString()).doesNotContain(refresh);
        // 256 bits em base64url (sem padding) = 43 caracteres
        assertThat(refresh).hasSize(43).matches("[A-Za-z0-9_-]+");

        // no banco só existe o hash
        List<RefreshToken> rows = tokens.findAll();
        assertThat(rows).hasSize(1);
        RefreshToken row = rows.get(0);
        assertThat(row.getTokenHash()).isEqualTo(sha256(refresh)).isNotEqualTo(refresh);
        assertThat(row.getUserAgent()).isEqualTo("JUnit/Agent");
        assertThat(row.getIp()).isNotBlank();
        assertThat(row.getCreatedAt()).isEqualTo(T0);
        assertThat(row.getLastUsedAt()).isEqualTo(T0);
        assertThat(row.getExpiresAt()).isEqualTo(T0.plus(Duration.ofDays(7)));
        assertThat(row.getAbsoluteExpiresAt()).isEqualTo(T0.plus(Duration.ofDays(30)));
        assertThat(row.getRevokedAt()).isNull();

        // o access token autentica nas rotas protegidas; sem ele, 401
        assertThat(me(access)).isEqualTo(200);
        assertThat(me(null)).isEqualTo(401);
    }

    @Test
    void login_failuresAreGeneric_sameResponseForWrongPasswordAndUnknownEmail() throws Exception {
        MvcResult wrongPassword = login(ADMIN_EMAIL, "senha-errada");
        MvcResult unknownEmail = login("ninguem@test.com", "qualquer");

        assertThat(wrongPassword.getResponse().getStatus()).isEqualTo(401);
        assertThat(unknownEmail.getResponse().getStatus()).isEqualTo(401);
        String m1 = json.readTree(wrongPassword.getResponse().getContentAsString()).get("message").asText();
        String m2 = json.readTree(unknownEmail.getResponse().getContentAsString()).get("message").asText();
        assertThat(m1).isEqualTo(m2).isEqualTo("Invalid email or password");
        assertThat(wrongPassword.getResponse().getHeaders("Set-Cookie")).isEmpty();
        assertThat(tokens.count()).isZero();
    }

    // ───────────────────── refresh: sessão deslizante ─────────────────────

    @Test
    void refresh_resetsTheInactivityDeadline_butNeverTheAbsoluteOne() throws Exception {
        MvcResult login = login();
        String t1 = cookieValue(login);

        clock.advance(Duration.ofDays(5));
        MvcResult refreshed = refresh(t1);

        assertThat(refreshed.getResponse().getStatus()).isEqualTo(200);
        String t2 = cookieValue(refreshed);
        assertThat(t2).isNotEqualTo(t1);
        assertThat(me(accessToken(refreshed))).isEqualTo(200);

        RefreshToken second = byToken(t2);
        assertThat(second.getExpiresAt()).isEqualTo(clock.instant().plus(Duration.ofDays(7))); // reiniciou
        assertThat(second.getAbsoluteExpiresAt()).isEqualTo(T0.plus(Duration.ofDays(30)));      // não mudou
        assertThat(second.getFamilyId()).isEqualTo(byToken(t1).getFamilyId());

        // sem o reset, o login de 11 dias atrás já teria vencido (limite de 7 dias)
        clock.advance(Duration.ofDays(6));
        MvcResult stillAlive = refresh(t2);
        assertThat(stillAlive.getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void refresh_afterTheInactivityPeriodWithoutUse_isRejected() throws Exception {
        String t1 = cookieValue(login());

        clock.advance(Duration.ofDays(7).plusSeconds(1));

        assertThat(refresh(t1).getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void refresh_hitsTheAbsoluteLimit_evenForAnAlwaysActiveSession() throws Exception {
        String token = cookieValue(login());
        Instant absolute = T0.plus(Duration.ofDays(30));

        // usa a sessão a cada 6 dias: nunca fica 7 dias inativa
        for (int day = 6; day <= 24; day += 6) {
            clock.set(T0.plus(Duration.ofDays(day)));
            MvcResult res = refresh(token);
            assertThat(res.getResponse().getStatus()).as("refresh no dia %d", day).isEqualTo(200);
            token = cookieValue(res);
        }

        // dia 24 + 7 passaria de 30: o prazo do token é limitado ao absoluto
        assertThat(byToken(token).getExpiresAt()).isEqualTo(absolute);

        // no dia 30 a sessão acabou, por mais ativa que estivesse
        clock.set(absolute);
        assertThat(refresh(token).getResponse().getStatus()).isEqualTo(401);
        // e o access token emitido antes também deixa de valer (sessão vencida no banco)
        assertThat(tokens.isSessionActive(byToken(token).getFamilyId(), clock.instant())).isFalse();
    }

    // ───────────────── rotação, reuso e janela de tolerância ─────────────────

    @Test
    void reusingARotatedToken_revokesTheWholeFamily() throws Exception {
        MvcResult login = login();
        String t1 = cookieValue(login);

        MvcResult rotated = refresh(t1);
        String t2 = cookieValue(rotated);
        String accessFromRotation = accessToken(rotated);
        assertThat(me(accessFromRotation)).isEqualTo(200);

        // passada a janela de tolerância, apresentar T1 de novo é reuso → roubo
        clock.advance(Duration.ofSeconds(11));
        MvcResult reuse = refresh(t1);
        assertThat(reuse.getResponse().getStatus()).isEqualTo(401);

        // a família inteira foi derrubada: o token "legítimo" T2 também morre...
        assertThat(refresh(t2).getResponse().getStatus()).isEqualTo(401);
        // ...e o access token ainda não expirado deixa de ser aceito na hora
        assertThat(me(accessFromRotation)).isEqualTo(401);

        List<RefreshToken> family = tokens.findAll();
        assertThat(family).hasSize(2).allSatisfy(t -> assertThat(t.getRevokedAt()).isNotNull());
    }

    @Test
    void reuseWithinTheGraceWindow_returnsAnAccessTokenWithoutRevokingOrRotating() throws Exception {
        String t1 = cookieValue(login());

        MvcResult winner = refresh(t1);
        String t2 = cookieValue(winner);

        clock.advance(Duration.ofSeconds(5)); // dentro dos 10s
        MvcResult concurrent = refresh(t1);

        assertThat(concurrent.getResponse().getStatus()).isEqualTo(200);
        assertThat(me(accessToken(concurrent))).isEqualTo(200);
        // não gera outro cookie: o browser já tem o T2 entregue à requisição vencedora
        assertThat(concurrent.getResponse().getHeaders("Set-Cookie")).isEmpty();
        assertThat(tokens.count()).isEqualTo(2); // nada de terceiro token

        // a sessão segue viva e T2 continua sendo o token válido
        assertThat(refresh(t2).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void concurrentRefreshFromTwoTabs_rotatesExactlyOnceAndLeavesOneValidToken() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // repete para aumentar a chance de as duas requisições realmente se sobreporem
            for (int round = 0; round < 10; round++) {
                tokens.deleteAll();
                String t1 = cookieValue(login());

                CountDownLatch ready = new CountDownLatch(2);
                CountDownLatch go = new CountDownLatch(1);
                Callable<MvcResult> tab = () -> {
                    ready.countDown();
                    go.await();
                    return refresh(t1);
                };
                Future<MvcResult> a = pool.submit(tab);
                Future<MvcResult> b = pool.submit(tab);
                ready.await();
                go.countDown();
                List<MvcResult> results = List.of(a.get(), b.get());

                // as duas abas recebem um access token válido...
                for (MvcResult r : results) {
                    assertThat(r.getResponse().getStatus()).isEqualTo(200);
                    assertThat(me(accessToken(r))).isEqualTo(200);
                }
                // ...mas só UMA rotacionou e recebeu o cookie novo
                long withNewCookie = results.stream()
                        .filter(r -> !r.getResponse().getHeaders("Set-Cookie").isEmpty()).count();
                assertThat(withNewCookie).as("round %d", round).isEqualTo(1);

                // no banco: T1 (rotacionado) + T2 (único ativo) — nunca dois tokens válidos
                List<RefreshToken> rows = tokens.findAll();
                assertThat(rows).hasSize(2);
                assertThat(rows.stream().filter(t -> t.getRotatedAt() == null && t.getRevokedAt() == null)).hasSize(1);
                assertThat(rows.stream().filter(t -> t.getRotatedAt() != null)).hasSize(1);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    // ───────────────────────────── CSRF ─────────────────────────────

    @Test
    void refresh_requiresATrustedOriginAndTheCustomHeader() throws Exception {
        String t1 = cookieValue(login());

        // sem Origin
        assertThat(mvc.perform(post("/auth/refresh").header("X-Requested-With", "fausto-web")
                .cookie(new Cookie("refresh_token", t1))).andReturn().getResponse().getStatus()).isEqualTo(403);
        // Origin de outro site
        assertThat(mvc.perform(post("/auth/refresh").header("Origin", "https://evil.example")
                .header("X-Requested-With", "fausto-web")
                .cookie(new Cookie("refresh_token", t1))).andReturn().getResponse().getStatus()).isEqualTo(403);
        // sem o header customizado
        assertThat(mvc.perform(post("/auth/refresh").header("Origin", ORIGIN)
                .cookie(new Cookie("refresh_token", t1))).andReturn().getResponse().getStatus()).isEqualTo(403);

        // as tentativas bloqueadas não consumiram o token
        assertThat(refresh(t1).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void refresh_withMissingOrGarbageCookie_getsTheSameGenericAnswer() throws Exception {
        MvcResult garbage = refresh("nao-e-um-token");
        MvcResult missing = mvc.perform(trustedRefresh()).andReturn();

        assertThat(garbage.getResponse().getStatus()).isEqualTo(401);
        assertThat(missing.getResponse().getStatus()).isEqualTo(401);
        assertThat(json.readTree(garbage.getResponse().getContentAsString()).get("message").asText())
                .isEqualTo(json.readTree(missing.getResponse().getContentAsString()).get("message").asText());
    }

    // ───────────────────────── logout e revogação ─────────────────────────

    @Test
    void logout_revokesTheSessionInTheDatabaseAndClearsTheCookie() throws Exception {
        MvcResult login = login();
        String refresh = cookieValue(login);
        String access = accessToken(login);

        MvcResult out = mvc.perform(post("/auth/logout").header("Authorization", "Bearer " + access)).andReturn();

        assertThat(out.getResponse().getStatus()).isEqualTo(200);
        assertThat(setCookie(out, "refresh_token")).contains("Max-Age=0", "Path=/auth/refresh");
        assertThat(tokens.findAll()).allSatisfy(t -> assertThat(t.getRevokedAt()).isNotNull());
        assertThat(refresh(refresh).getResponse().getStatus()).isEqualTo(401);
        assertThat(me(access)).isEqualTo(401); // vale imediatamente, sem esperar o access token expirar
    }

    @Test
    void logout_worksEvenWithAnExpiredAccessToken() throws Exception {
        MvcResult login = login();
        String refresh = cookieValue(login);
        String access = accessToken(login);

        clock.advance(Duration.ofMinutes(20)); // access token vencido (vida de 15 min)
        assertThat(me(access)).isEqualTo(401);

        mvc.perform(post("/auth/logout").header("Authorization", "Bearer " + access)).andReturn();

        assertThat(refresh(refresh).getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void logoutAll_revokesEverySessionOfTheUser() throws Exception {
        MvcResult phone = login();
        MvcResult laptop = login();

        MvcResult out = mvc.perform(post("/auth/logout-all")
                .header("Authorization", "Bearer " + accessToken(phone))).andReturn();

        assertThat(out.getResponse().getStatus()).isEqualTo(200);
        assertThat(refresh(cookieValue(phone)).getResponse().getStatus()).isEqualTo(401);
        assertThat(refresh(cookieValue(laptop)).getResponse().getStatus()).isEqualTo(401);
        assertThat(me(accessToken(laptop))).isEqualTo(401);

        // sem autenticação não revoga nada
        assertThat(mvc.perform(post("/auth/logout-all")).andReturn().getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void changingThePassword_revokesAllSessionsOfThatUser() throws Exception {
        User other = new User();
        other.setEmail("troca-senha@test.com");
        other.setPassword(passwordEncoder.encode("senha-antiga"));
        other.setRole(Role.ADMIN);
        other.setActive(true);
        other = users.save(other);

        MvcResult s1 = login("troca-senha@test.com", "senha-antiga");
        MvcResult s2 = login("troca-senha@test.com", "senha-antiga");

        MvcResult change = mvc.perform(put("/api/user/" + other.getId())
                .header("Authorization", "Bearer " + accessToken(s1))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"troca-senha@test.com\",\"password\":\"senha-nova\"}")).andReturn();
        assertThat(change.getResponse().getStatus()).isEqualTo(200);

        assertThat(refresh(cookieValue(s1)).getResponse().getStatus()).isEqualTo(401);
        assertThat(refresh(cookieValue(s2)).getResponse().getStatus()).isEqualTo(401);
        assertThat(me(accessToken(s2))).isEqualTo(401);
        assertThat(login("troca-senha@test.com", "senha-antiga").getResponse().getStatus()).isEqualTo(401);
        assertThat(login("troca-senha@test.com", "senha-nova").getResponse().getStatus()).isEqualTo(200);
    }

    // ───────────────────────────── limpeza ─────────────────────────────

    @Test
    void cleanupJob_removesExpiredTokensAndKeepsLiveOnes() throws Exception {
        String old = cookieValue(login());   // sessão A, vai vencer por inatividade
        clock.advance(Duration.ofDays(6));
        String live = cookieValue(login());  // sessão B, recém-criada
        clock.advance(Duration.ofDays(2));   // dia 8: A passou de 7 dias sem uso, B tem 2 dias

        int removed = refreshService.purgeExpired();

        assertThat(removed).isEqualTo(1);
        assertThat(tokens.findAll()).singleElement()
                .satisfies(t -> assertThat(t.getTokenHash()).isEqualTo(sha256(live)).isNotEqualTo(sha256(old)));
    }

    // ───────────────────────────── helpers ─────────────────────────────

    private MvcResult login() throws Exception {
        return login(ADMIN_EMAIL, ADMIN_PASSWORD);
    }

    private MvcResult login(String email, String password) throws Exception {
        return mvc.perform(post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(credentials(email, password))).andReturn();
    }

    private MockHttpServletRequestBuilder trustedRefresh() {
        return post("/auth/refresh").header("Origin", ORIGIN).header("X-Requested-With", "fausto-web");
    }

    private MvcResult refresh(String refreshToken) throws Exception {
        return mvc.perform(trustedRefresh().cookie(new Cookie("refresh_token", refreshToken))).andReturn();
    }

    private int me(String accessToken) throws Exception {
        MockHttpServletRequestBuilder req = get("/auth/me");
        if (accessToken != null) req.header("Authorization", "Bearer " + accessToken);
        return mvc.perform(req).andReturn().getResponse().getStatus();
    }

    private String accessToken(MvcResult res) throws Exception {
        return json.readTree(res.getResponse().getContentAsString()).get("accessToken").asText();
    }

    private String setCookie(MvcResult res, String name) {
        return new ArrayList<>(res.getResponse().getHeaders("Set-Cookie")).stream()
                .filter(h -> h.startsWith(name + "="))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Set-Cookie " + name + " ausente"));
    }

    private String cookieValue(MvcResult res) {
        String header = setCookie(res, "refresh_token");
        return header.substring("refresh_token=".length(), header.indexOf(';'));
    }

    private RefreshToken byToken(String raw) {
        return tokens.findAll().stream()
                .filter(t -> t.getTokenHash().equals(sha256(raw)))
                .max(Comparator.comparing(RefreshToken::getCreatedAt))
                .orElseThrow(() -> new AssertionError("token não encontrado no banco"));
    }

    private static String credentials(String email, String password) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
    }

    private static String sha256(String raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
