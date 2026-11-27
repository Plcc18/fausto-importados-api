package com.example.fausto_importados_api.security.ratelimit;

import com.example.fausto_importados_api.model.User;
import com.example.fausto_importados_api.model.enums.Role;
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
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Rate limiting de ponta a ponta (filtro, controller de login, armazenamento em memória), com limites pequenos.
 * O tempo é controlado por um {@link MutableClock} que só avança (os baldes guardam o instante da última ficha).
 * Cada teste usa IPs e e-mails próprios, para não depender da ordem de execução.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:ratelimit;DB_CLOSE_DELAY=-1",
        "security.rate-limit.trusted-proxy-hops=1",
        "security.rate-limit.api-anon=6/1m",
        "security.rate-limit.public-read=3/1m",
        "security.rate-limit.api-user=5/1m",
        "security.rate-limit.login-ip=4/15m",
        "security.rate-limit.login-email=5/15m",
        "security.rate-limit.login-admin-email=3/15m",
        "security.rate-limit.refresh=2/1m",
        "security.rate-limit.public-write=2/1h",
        "security.rate-limit.sensitive-paths=/auth/register"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RateLimitTest {

    private static final Instant T0 = Instant.parse("2026-03-01T12:00:00Z");
    private static final String PASSWORD = "senha-forte-123";
    private static final AtomicInteger NEXT = new AtomicInteger(1);

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
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired ObjectMapper json;

    /** Cada teste recebe IPs que ninguém mais usa. */
    private static String freshIp() {
        int n = NEXT.getAndIncrement();
        return "198.51." + (n / 250) + "." + (n % 250 + 1);
    }

    private static String freshEmail(String prefix) {
        return prefix + "-" + NEXT.getAndIncrement() + "@test.com";
    }

    @BeforeEach
    void noop() {
        // o relógio nunca volta: os baldes guardam o instante da última ficha
    }

    // ───────────────────────── limite atingido → 429 ─────────────────────────

    @Test
    void anonymousLimit_isEnforced_with429_retryAfter_rateLimitHeaders_andAGenericMessage() throws Exception {
        String ip = freshIp();

        for (int i = 1; i <= 6; i++) {
            MockHttpServletResponse ok = anon(ip);
            assertThat(ok.getStatus()).as("requisição %d", i).isNotEqualTo(429);
            assertThat(ok.getHeader("RateLimit-Limit")).isEqualTo("6");
            assertThat(ok.getHeader("RateLimit-Remaining")).isEqualTo(String.valueOf(6 - i));
            assertThat(ok.getHeader("RateLimit-Reset")).isNotBlank();
            assertThat(ok.getHeader("Retry-After")).isNull();
        }

        MockHttpServletResponse limited = anon(ip);
        assertThat(limited.getStatus()).isEqualTo(429);
        assertThat(limited.getHeader("Retry-After")).isEqualTo("10"); // 6 por minuto = uma ficha a cada 10 s
        assertThat(limited.getHeader("RateLimit-Limit")).isEqualTo("6");
        assertThat(limited.getHeader("RateLimit-Remaining")).isEqualTo("0");
        assertThat(limited.getHeader("RateLimit-Reset")).isEqualTo("60");
        assertThat(limited.getContentType()).contains(MediaType.APPLICATION_JSON_VALUE);
        String body = limited.getContentAsString();
        assertThat(json.readTree(body).get("message").asText()).isEqualTo("Too many requests. Try again later.");
        // a mensagem não diz qual regra, qual chave nem o limite
        assertThat(body).doesNotContain(ip).doesNotContain("api-anon");
    }

    // ───────────────────────── reset após a janela ─────────────────────────

    @Test
    void afterTheWindow_theLimitResets() throws Exception {
        String ip = freshIp();
        for (int i = 0; i < 6; i++) {
            anon(ip);
        }
        assertThat(anon(ip).getStatus()).isEqualTo(429);

        // 10 s = uma ficha de volta (6 por minuto)
        clock.advance(Duration.ofSeconds(10));
        assertThat(anon(ip).getStatus()).isNotEqualTo(429);
        assertThat(anon(ip).getStatus()).isEqualTo(429);

        // a janela inteira: volta cheio, e não acumula além do máximo
        clock.advance(Duration.ofMinutes(5));
        for (int i = 0; i < 6; i++) {
            assertThat(anon(ip).getStatus()).as("após a janela, requisição %d", i + 1).isNotEqualTo(429);
        }
        assertThat(anon(ip).getStatus()).isEqualTo(429);
    }

    // ───────────────────── limite por usuário, independente do IP ─────────────────────

    @Test
    void userLimit_followsTheUser_notTheIp() throws Exception {
        String adminToken = loginToken(newUser(Role.ADMIN), freshIp());
        String otherToken = loginToken(newUser(Role.USER), freshIp());
        String ipA = freshIp();
        String ipB = freshIp();

        for (int i = 0; i < 5; i++) {
            assertThat(me(adminToken, ipA).getStatus()).as("requisição %d", i + 1).isEqualTo(200);
        }
        // o admin esgotou o limite: de OUTRO IP continua bloqueado, o limite é dele
        MockHttpServletResponse fromAnotherIp = me(adminToken, ipB);
        assertThat(fromAnotherIp.getStatus()).isEqualTo(429);
        assertThat(fromAnotherIp.getHeader("Retry-After")).isNotBlank();

        // outro usuário, mesmo no IP que o admin usou: não é afetado
        assertThat(me(otherToken, ipA).getStatus()).isEqualTo(200);
        // e o IP de quem esgotou segue livre para quem não está logado
        assertThat(anon(ipA).getStatus()).isNotEqualTo(429);
    }

    // ───────────────────── campanha da loja ─────────────────────

    @Test
    void theStoreCampaign_hasItsOwnLimitPerIp() throws Exception {
        String ip = freshIp();

        for (int i = 1; i <= 3; i++) {
            MockHttpServletResponse ok = campaign(ip);
            assertThat(ok.getStatus()).as("requisição %d", i).isEqualTo(200);
            assertThat(ok.getHeader("RateLimit-Limit")).isEqualTo("3");
        }
        MockHttpServletResponse limited = campaign(ip);

        assertThat(limited.getStatus()).isEqualTo(429);
        assertThat(limited.getHeader("Retry-After")).isNotBlank();
        assertThat(campaign(freshIp()).getStatus()).isEqualTo(200);
    }

    @Test
    void theStoreCampaign_andTheCatalog_doNotSpendEachOthersQuota() throws Exception {
        String visitor = freshIp();
        for (int i = 0; i < 3; i++) {
            campaign(visitor);
        }
        assertThat(campaign(visitor).getStatus()).isEqualTo(429);
        for (int i = 1; i <= 6; i++) { // a cota geral continua inteira
            assertThat(anon(visitor).getStatus()).as("catálogo %d", i).isNotEqualTo(429);
        }
        assertThat(anon(visitor).getStatus()).isEqualTo(429);

        String other = freshIp();
        for (int i = 0; i < 7; i++) {
            anon(other);
        }
        assertThat(campaign(other).getStatus()).isEqualTo(200);
    }

    // ───────────────────── X-Forwarded-For forjado não burla ─────────────────────

    @Test
    void forgedForwardedFor_doesNotBypassTheLimit() throws Exception {
        String realIp = freshIp(); // o que o proxy acrescenta no fim (confiável)

        // o atacante manda um IP diferente a cada requisição à esquerda; o proxy acrescenta o real no fim
        for (int i = 0; i < 6; i++) {
            MockHttpServletResponse res = mvc.perform(get("/api/product")
                    .header("X-Forwarded-For", "10.99.99." + i + ", " + realIp)).andReturn().getResponse();
            assertThat(res.getStatus()).isNotEqualTo(429);
        }
        MockHttpServletResponse forged = mvc.perform(get("/api/product")
                .header("X-Forwarded-For", "10.99.99.200, " + realIp)).andReturn().getResponse();
        assertThat(forged.getStatus()).as("IP forjado à esquerda não cria balde novo").isEqualTo(429);

        // sem cabeçalho nenhum, vale o endereço da conexão: também é um balde só
        for (int i = 0; i < 6; i++) {
            mvc.perform(get("/api/product").with(request -> {
                request.setRemoteAddr("192.0.2.77");
                return request;
            }));
        }
        MockHttpServletResponse noHeader = mvc.perform(get("/api/product").with(request -> {
            request.setRemoteAddr("192.0.2.77");
            return request;
        })).andReturn().getResponse();
        assertThat(noHeader.getStatus()).isEqualTo(429);

        // quem tem outro IP real (último item) é outra pessoa
        assertThat(anon(freshIp()).getStatus()).isNotEqualTo(429);
    }

    // ───────────────────────── login: por IP e por e-mail ─────────────────────────

    @Test
    void login_isLimitedPerIp_acrossDifferentEmails() throws Exception {
        String ip = freshIp();

        for (int i = 0; i < 4; i++) {
            assertThat(login(freshEmail("ninguem"), "x", ip).getStatus()).as("tentativa %d", i + 1).isEqualTo(401);
        }
        // e-mails diferentes, mesmo IP: a quinta tentativa já é bloqueada
        MockHttpServletResponse blocked = login(freshEmail("ninguem"), "x", ip);
        assertThat(blocked.getStatus()).isEqualTo(429);
        assertThat(blocked.getHeader("Retry-After")).isNotBlank();
    }

    @Test
    void adminLogin_isLimitedPerEmail_acrossDifferentIps_andUnknownEmailsLookTheSame() throws Exception {
        String admin = newUser(Role.ADMIN);
        String ghost = freshEmail("fantasma");

        for (String email : new String[]{admin, ghost}) {
            // IP novo a cada tentativa: só o limite por e-mail pode barrar
            for (int i = 0; i < 3; i++) {
                assertThat(login(email, "errada", freshIp()).getStatus()).isEqualTo(401);
            }
            MockHttpServletResponse blocked = login(email, PASSWORD, freshIp());
            assertThat(blocked.getStatus()).as("4ª tentativa de %s", email.startsWith("fantasma") ? "e-mail inexistente" : "admin")
                    .isEqualTo(429);
            assertThat(blocked.getHeader("Retry-After")).isNotBlank();
            // a senha certa não passa enquanto bloqueado
            assertThat(blocked.getContentAsString()).doesNotContain("accessToken");
        }
    }

    @Test
    void regularAccount_getsTheMorePermissiveEmailLimit() throws Exception {
        String regular = newUser(Role.USER);

        for (int i = 0; i < 4; i++) {
            assertThat(login(regular, "errada", freshIp()).getStatus()).isEqualTo(401);
        }
        assertThat(login(regular, PASSWORD, freshIp()).getStatus()).isEqualTo(200); // 5ª tentativa ainda cabe
        assertThat(login(regular, PASSWORD, freshIp()).getStatus()).isEqualTo(429);
    }

    // ───────────────────────── refresh por sessão, escrita pública ─────────────────────────

    @Test
    void refresh_isLimitedPerSession_notPerIp() throws Exception {
        String ip = freshIp();

        for (int i = 0; i < 2; i++) {
            assertThat(refresh("sessao-a", ip).getStatus()).isNotEqualTo(429);
        }
        assertThat(refresh("sessao-a", ip).getStatus()).isEqualTo(429);
        // outra sessão, mesmo IP: balde próprio
        assertThat(refresh("sessao-b", ip).getStatus()).isNotEqualTo(429);
    }

    @Test
    void publicWrite_isLimitedPerIp() throws Exception {
        String ip = freshIp();

        for (int i = 0; i < 2; i++) {
            assertThat(order(ip).getStatus()).isNotEqualTo(429);
        }
        MockHttpServletResponse blocked = order(ip);
        assertThat(blocked.getStatus()).isEqualTo(429);
        assertThat(blocked.getHeader("Retry-After")).isNotBlank();
        // leituras públicas do mesmo IP não são afetadas por esta regra
        assertThat(anon(ip).getStatus()).isNotEqualTo(429);
    }

    @Test
    void publicWrite_alsoCoversTheConfiguredSensitivePaths() throws Exception {
        String ip = freshIp();

        // cadastro / recuperação de senha ainda não existem como rotas: a regra já vale para os caminhos configurados
        for (int i = 0; i < 2; i++) {
            assertThat(mvc.perform(post("/auth/register").header("X-Forwarded-For", ip)).andReturn()
                    .getResponse().getStatus()).isNotEqualTo(429);
        }
        assertThat(mvc.perform(post("/auth/register").header("X-Forwarded-For", ip)).andReturn()
                .getResponse().getStatus()).isEqualTo(429);
    }

    // ───────────────────────── área admin oculta ─────────────────────────

    @Test
    void hiddenAdminArea_is404ForNonAdmins_evenWhenTheyAreOverTheLimit() throws Exception {
        String userToken = loginToken(newUser(Role.USER), freshIp());
        String ip = freshIp();

        // esgota o limite do usuário comum em outra rota
        for (int i = 0; i < 5; i++) {
            me(userToken, ip);
        }
        assertThat(me(userToken, ip).getStatus()).isEqualTo(429);

        // a rota oculta responde 404 sempre (nunca 429, nunca 403), bloqueado ou não
        for (int i = 0; i < 12; i++) {
            MockHttpServletResponse res = admin(userToken, "/api/admin/audit", ip);
            assertThat(res.getStatus()).as("tentativa %d", i + 1).isEqualTo(404);
            assertThat(res.getHeader("Retry-After")).isNull();
            assertThat(res.getHeader("RateLimit-Limit")).isNull();
        }
        assertThat(admin(userToken, "/api/admin/qualquer-coisa", ip).getStatus()).isEqualTo(404);
        assertThat(admin(userToken, "/API/ADMIN/audit", ip).getStatus()).isEqualTo(404);
    }

    @Test
    void hiddenAdminArea_stillWorksForAdmins_andAnonymousGetsTheUsual401() throws Exception {
        String adminToken = loginToken(newUser(Role.ADMIN), freshIp());

        assertThat(admin(adminToken, "/api/admin/audit", freshIp()).getStatus()).isEqualTo(200);
        assertThat(anonAdmin(freshIp()).getStatus()).isEqualTo(401);
    }

    // ───────────────────────── auxiliares ─────────────────────────

    private MockHttpServletResponse anon(String ip) throws Exception {
        return mvc.perform(get("/api/product").header("X-Forwarded-For", ip)).andReturn().getResponse();
    }

    private MockHttpServletResponse campaign(String ip) throws Exception {
        return mvc.perform(get("/api/campaigns/active").header("X-Forwarded-For", ip)).andReturn().getResponse();
    }

    private MockHttpServletResponse anonAdmin(String ip) throws Exception {
        return mvc.perform(get("/api/admin/audit").header("X-Forwarded-For", ip)).andReturn().getResponse();
    }

    private MockHttpServletResponse me(String token, String ip) throws Exception {
        return mvc.perform(get("/auth/me").header("Authorization", "Bearer " + token)
                .header("X-Forwarded-For", ip)).andReturn().getResponse();
    }

    private MockHttpServletResponse admin(String token, String path, String ip) throws Exception {
        return mvc.perform(get(path).header("Authorization", "Bearer " + token)
                .header("X-Forwarded-For", ip)).andReturn().getResponse();
    }

    private MockHttpServletResponse login(String email, String password, String ip) throws Exception {
        MockHttpServletRequestBuilder request = post("/auth/login")
                .header("X-Forwarded-For", ip)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(java.util.Map.of("email", email, "password", password)));
        return mvc.perform(request).andReturn().getResponse();
    }

    private String loginToken(String email, String ip) throws Exception {
        MockHttpServletResponse res = login(email, PASSWORD, ip);
        assertThat(res.getStatus()).as("login de %s", email).isEqualTo(200);
        return json.readTree(res.getContentAsString()).get("accessToken").asText();
    }

    private MockHttpServletResponse refresh(String cookie, String ip) throws Exception {
        return mvc.perform(post("/auth/refresh")
                .header("X-Forwarded-For", ip)
                .header("Origin", "http://localhost:5173")
                .header("X-Requested-With", "fausto-web")
                .cookie(new Cookie("refresh_token", cookie))).andReturn().getResponse();
    }

    private MockHttpServletResponse order(String ip) throws Exception {
        return mvc.perform(post("/api/orders").header("X-Forwarded-For", ip)
                .contentType(MediaType.APPLICATION_JSON).content("{\"customerName\":\"t\",\"customerWhatsapp\":\"1\",\"paymentMethod\":\"PIX\",\"total\":0,\"items\":[]}")).andReturn().getResponse();
    }

    private String newUser(Role role) {
        String email = freshEmail(role.name().toLowerCase());
        User user = new User();
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode(PASSWORD));
        user.setRole(role);
        user.setActive(true);
        users.save(user);
        return email;
    }
}
