package com.example.fausto_importados_api.security;

import com.example.fausto_importados_api.model.User;
import com.example.fausto_importados_api.model.enums.Role;
import com.example.fausto_importados_api.repository.AuthThrottleRepository;
import com.example.fausto_importados_api.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * Matriz de acesso: cada rota administrativa responde 401 sem autenticação, 403 para um usuário comum
 * (Role.USER) e só admite um administrador. As rotas públicas continuam públicas.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:accesscontrol;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AccessControlTest {

    private static final String ID = "00000000-0000-0000-0000-000000000001";
    private static final String USER_EMAIL = "cliente@test.com";
    private static final String USER_PASSWORD = "senha-do-cliente-123";

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired AuthThrottleRepository throttles;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired ObjectMapper json;

    private String adminToken;
    private String userToken;

    @BeforeEach
    void setUp() throws Exception {
        throttles.deleteAll();
        if (!users.existsByEmail(USER_EMAIL)) {
            User u = new User();
            u.setEmail(USER_EMAIL);
            u.setPassword(passwordEncoder.encode(USER_PASSWORD));
            u.setRole(Role.USER);
            u.setActive(true);
            users.save(u);
        }
        adminToken = loginToken("admin@test.com", "test-password");
        userToken = loginToken(USER_EMAIL, USER_PASSWORD);
    }

    /** Toda rota que NÃO é pública. */
    static Stream<String[]> adminOnlyEndpoints() {
        return Stream.of(
                new String[]{"GET", "/api/user"},
                new String[]{"GET", "/api/user/" + ID},
                new String[]{"GET", "/api/user/email/alguem@test.com"},
                new String[]{"POST", "/api/user"},
                new String[]{"PUT", "/api/user/" + ID},
                new String[]{"DELETE", "/api/user/" + ID},
                new String[]{"GET", "/api/orders"},
                new String[]{"GET", "/api/orders/filter?status=PENDING"},
                new String[]{"GET", "/api/orders/report"},
                new String[]{"GET", "/api/orders/sales-stats"},
                new String[]{"POST", "/api/orders/" + ID + "/complete"},
                new String[]{"POST", "/api/orders/" + ID + "/cancel"},
                new String[]{"DELETE", "/api/orders/clear-history"},
                new String[]{"DELETE", "/api/orders/reset-sales"},
                new String[]{"DELETE", "/api/orders/reset-report"},
                new String[]{"POST", "/api/product"},
                new String[]{"POST", "/api/product/decrease-stock"},
                new String[]{"PUT", "/api/product/" + ID},
                new String[]{"PATCH", "/api/product/" + ID},
                new String[]{"DELETE", "/api/product/" + ID},
                new String[]{"POST", "/api/upload"},
                new String[]{"GET", "/api-docs"},
                new String[]{"GET", "/swagger-ui.html"},
                new String[]{"GET", "/api/rota-que-nao-existe"}
        );
    }

    @ParameterizedTest(name = "anônimo → 401 em {0} {1}")
    @MethodSource("adminOnlyEndpoints")
    void anonymous_isUnauthorized(String method, String path) throws Exception {
        assertThat(call(method, path, null).getResponse().getStatus()).isEqualTo(401);
    }

    @ParameterizedTest(name = "usuário comum → 403 em {0} {1}")
    @MethodSource("adminOnlyEndpoints")
    void regularUser_isForbidden(String method, String path) throws Exception {
        MvcResult res = call(method, path, userToken);

        assertThat(res.getResponse().getStatus()).isEqualTo(403);
        assertThat(json.readTree(res.getResponse().getContentAsString()).get("status").asInt()).isEqualTo(403);
    }

    @Test
    void admin_reachesTheAdminRoutes() throws Exception {
        assertThat(call("GET", "/api/user", adminToken).getResponse().getStatus()).isEqualTo(200);
        assertThat(call("GET", "/api/orders", adminToken).getResponse().getStatus()).isEqualTo(200);
        assertThat(call("GET", "/api/orders/sales-stats", adminToken).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void invalidOrGarbageTokens_are401_never403() throws Exception {
        assertThat(call("GET", "/api/user", "isto.nao.e-um-jwt").getResponse().getStatus()).isEqualTo(401);
        assertThat(call("GET", "/api/user", adminToken + "x").getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void publicRoutesStayPublic() throws Exception {
        assertThat(call("GET", "/api/product", null).getResponse().getStatus()).isEqualTo(200);
        assertThat(call("GET", "/actuator/health", null).getResponse().getStatus()).isEqualTo(200);
        // Criar pedido é público. O que importa aqui é passar pela segurança: se a requisição chegou ao
        // controller (mesmo que um pedido vazio acabe em erro de negócio/banco), não foi barrada com 401/403.
        int create;
        try {
            create = mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content("{\"items\":[]}"))
                    .andReturn().getResponse().getStatus();
        } catch (jakarta.servlet.ServletException reachedTheController) {
            create = 500;
        }
        assertThat(create).isNotIn(401, 403);
    }

    @Test
    void aRegularUserCanUseTheSessionEndpointsButIsNotAnAdmin() throws Exception {
        MvcResult me = call("GET", "/auth/me", userToken);

        assertThat(me.getResponse().getStatus()).isEqualTo(200);
        assertThat(json.readTree(me.getResponse().getContentAsString()).get("role").asText()).isEqualTo("USER");
        assertThat(call("POST", "/auth/logout-all", userToken).getResponse().getStatus()).isEqualTo(200);
        assertThat(call("GET", "/auth/me", userToken).getResponse().getStatus()).isEqualTo(401); // sessão revogada
    }

    @Test
    void apiResponsesCarrySecurityHeaders_andNeverCacheAuthenticatedData() throws Exception {
        MvcResult ok = call("GET", "/api/user", adminToken);
        MvcResult denied = call("GET", "/api/user", null);

        for (MvcResult res : new MvcResult[]{ok, denied}) {
            assertThat(res.getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
            assertThat(res.getResponse().getHeader("X-Frame-Options")).isEqualTo("DENY");
            assertThat(res.getResponse().getHeader("Cache-Control")).contains("no-store");
        }
    }

    @Test
    void corsAllowsTheConfiguredFrontendOnly() throws Exception {
        MvcResult allowed = mvc.perform(options("/api/product")
                .header("Origin", "http://localhost:5173")
                .header("Access-Control-Request-Method", "GET")
                .header("Access-Control-Request-Headers", "authorization")).andReturn();
        MvcResult denied = mvc.perform(options("/api/product")
                .header("Origin", "https://evil.example")
                .header("Access-Control-Request-Method", "GET")).andReturn();

        assertThat(allowed.getResponse().getStatus()).isEqualTo(200);
        assertThat(allowed.getResponse().getHeader("Access-Control-Allow-Origin")).isEqualTo("http://localhost:5173");
        assertThat(allowed.getResponse().getHeader("Access-Control-Allow-Credentials")).isEqualTo("true");
        assertThat(denied.getResponse().getStatus()).isEqualTo(403);
        assertThat(denied.getResponse().getHeader("Access-Control-Allow-Origin")).isNull();
    }

    // ───────────────────────────── helpers ─────────────────────────────

    private MvcResult call(String method, String path, String token) throws Exception {
        MockHttpServletRequestBuilder req = request(HttpMethod.valueOf(method), path);
        if (!"GET".equals(method)) {
            req.contentType(MediaType.APPLICATION_JSON).content("{}");
        }
        if (token != null) {
            req.header("Authorization", "Bearer " + token);
        }
        return mvc.perform(req).andReturn();
    }

    private String loginToken(String email, String password) throws Exception {
        MvcResult res = mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", email, "password", password)))).andReturn();
        assertThat(res.getResponse().getStatus()).as("login de %s", email).isEqualTo(200);
        return json.readTree(res.getResponse().getContentAsString()).get("accessToken").asText();
    }
}
