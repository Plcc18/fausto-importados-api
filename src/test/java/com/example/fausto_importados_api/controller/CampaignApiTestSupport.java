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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * Base dos testes das rotas {@code /api/admin/campaigns}: H2, relógio fixo e as exigências de produção para versão e
 * Idempotency-Key ligadas.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:campaignapi;DB_CLOSE_DELAY=-1",
        "app.concurrency.require-version=true",
        "app.concurrency.require-idempotency-key=true"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(CampaignApiTestSupport.TestClockConfig.class)
abstract class CampaignApiTestSupport {

    static final Instant NOW = Instant.parse("2026-11-20T12:00:00Z");
    static final String BASE = "/api/admin/campaigns";
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

    record Admin(User user, String token) {
    }

    record Res(int status, JsonNode body, Map<String, String> headers) {
        String code() {
            return body.path("code").asText();
        }

        List<String> fields() {
            List<String> names = new ArrayList<>();
            body.path("fields").forEach(f -> names.add(f.path("field").asText()));
            return names;
        }
    }

    Admin admin;

    @BeforeEach
    void signInAnAdmin() throws Exception {
        campaigns.deleteAll();
        admin = signIn(Role.ADMIN);
    }

    // ───────────────────────────── dados ─────────────────────────────

    static Instant days(int days) {
        return NOW.plus(days, ChronoUnit.DAYS);
    }

    static String newKey() {
        return UUID.randomUUID().toString();
    }

    /** Um corpo completo, como o painel enviaria. */
    Map<String, Object> complete(String name) {
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

    /** Campanha completa gravada direto no banco, em qualquer situação. */
    String stored(CampaignStatus status, int priority, Instant start, Instant end) {
        Campaign campaign = CampaignFixtures.complete();
        campaign.setStatus(status);
        campaign.setPriority(priority);
        campaign.setStartsAt(start);
        campaign.setEndsAt(end);
        return campaigns.save(campaign).getId().toString();
    }

    String named(String name, CampaignStatus status, CampaignType type, Instant start, Instant end) {
        Campaign campaign = status == CampaignStatus.PUBLISHED ? CampaignFixtures.complete() : CampaignFixtures.draft();
        campaign.setName(name);
        campaign.setType(type);
        campaign.setStatus(status);
        campaign.setStartsAt(start);
        campaign.setEndsAt(end);
        return campaigns.save(campaign).getId().toString();
    }

    // ───────────────────────────── chamadas ─────────────────────────────

    int version(String id) throws Exception {
        return detail(id).path("version").asInt();
    }

    JsonNode detail(String id) throws Exception {
        return get(BASE + "/" + id).body();
    }

    /** O documento atual com outro nome, como o painel enviaria. */
    Map<String, Object> edit(String id, String name) throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> body = json.convertValue(detail(id), LinkedHashMap.class);
        body.put("name", name);
        return body;
    }

    Res create(Object body) throws Exception {
        return create(body, newKey());
    }

    Res create(Object body, String key) throws Exception {
        return call("POST", BASE, admin.token(), body, Map.of("Idempotency-Key", key));
    }

    Res put(String id, Object body) throws Exception {
        return call("PUT", BASE + "/" + id, admin.token(), body, Map.of());
    }

    Res get(String url) throws Exception {
        return call("GET", url, admin.token(), null, Map.of());
    }

    Res call(String method, String url, String token, Object body, Map<String, String> headers) throws Exception {
        // URI pronta: um template codificaria de novo o que já vem codificado (%25, %2C)
        MockHttpServletRequestBuilder builder = request(HttpMethod.valueOf(method), URI.create(url));
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

    List<String> auditedActions(String campaignId) {
        return auditLog.findByEntityTypeOrderByOccurredAtDesc("CAMPAIGN", Pageable.unpaged()).getContent().stream()
                .filter(row -> campaignId.equals(row.getEntityId()))
                .map(row -> row.getAction())
                .toList();
    }

    Admin signIn(Role role) throws Exception {
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

    /** Liga o 2FA do admin (o token que ele já tem continua válido) e devolve a prova para o header X-Reauth. */
    String requireSecondFactor() {
        User user = users.findById(admin.user().getId()).orElseThrow();
        user.setTotpEnabled(true);
        users.save(user);
        return jwtService.generateReauthToken(user, jwtService.parse(admin.token()).sessionId());
    }
}
