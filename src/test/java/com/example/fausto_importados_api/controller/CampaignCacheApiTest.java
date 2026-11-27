package com.example.fausto_importados_api.controller;

import com.example.fausto_importados_api.model.Campaign;
import com.example.fausto_importados_api.model.Product;
import com.example.fausto_importados_api.model.enums.CampaignStatus;
import com.example.fausto_importados_api.model.enums.Category;
import com.example.fausto_importados_api.repository.ProductRepository;
import com.example.fausto_importados_api.support.CampaignFixtures;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static com.example.fausto_importados_api.support.CampaignFixtures.collection;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cache da rota pública. O limitador de requisições fica no banco, como em produção sem Redis, para provar que as
 * visitas repetidas não consultam o banco.
 */
@TestPropertySource(properties = "security.rate-limit.store=jdbc")
@Import(CampaignCacheApiTest.CountingDataSourceConfig.class)
class CampaignCacheApiTest extends CampaignApiTestSupport {

    private static final String ACTIVE = "/api/campaigns/active";
    private static final AtomicInteger CONNECTIONS = new AtomicInteger();

    /** Conta as conexões pegas do pool: sem uma, não há consulta ao banco. */
    @TestConfiguration
    static class CountingDataSourceConfig {
        @Bean
        static BeanPostProcessor connectionCounter() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (!(bean instanceof DataSource dataSource) || !beanName.equals("dataSource")) {
                        return bean;
                    }
                    return new DelegatingDataSource(dataSource) {
                        @Override
                        public Connection getConnection() throws SQLException {
                            CONNECTIONS.incrementAndGet();
                            return super.getConnection();
                        }
                    };
                }
            };
        }
    }

    @Autowired ProductRepository products;

    // ───────────────────────────── banco ─────────────────────────────

    @Test
    void repeatedVisitsDoNotQueryTheDatabase() throws Exception {
        stored(CampaignStatus.PUBLISHED, 1, days(-1), days(5));
        CONNECTIONS.set(0);
        String etag = visit().getHeader(HttpHeaders.ETAG);
        assertThat(CONNECTIONS.get()).as("a primeira visita lê o banco").isPositive();
        CONNECTIONS.set(0);

        for (int i = 0; i < 5; i++) {
            assertThat(visit().getStatus()).isEqualTo(200);
            assertThat(visit(etag).getStatus()).isEqualTo(304);
        }

        assertThat(CONNECTIONS.get()).isZero();
    }

    // ───────────────────────────── cabeçalhos ─────────────────────────────

    @Test
    void theAnswerMayBeCachedForAMinute() throws Exception {
        MockHttpServletResponse res = visit();

        assertThat(res.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("max-age=60, public");
        assertThat(res.getHeader(HttpHeaders.ETAG)).matches("W/\"[0-9a-f]{32}\"");
        assertThat(res.getHeader(HttpHeaders.DATE)).isEqualTo(httpDate(NOW));
        // o padrão do Spring Security (no-store) não vale aqui
        assertThat(res.getHeader(HttpHeaders.PRAGMA)).isNull();
        assertThat(res.getHeader(HttpHeaders.EXPIRES)).isNull();
        assertThat(res.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
    }

    @Test
    void theFrontEndMayReadTheServerDate() throws Exception {
        MockHttpServletResponse res = mvc.perform(
                        MockMvcRequestBuilders.get(ACTIVE).header("Origin", "http://localhost:5173"))
                .andReturn().getResponse();

        assertThat(res.getHeader(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS)).contains("Date");
        assertThat(res.getHeaders(HttpHeaders.VARY)).contains("Origin"); // cache público com CORS
    }

    @Test
    void aMatchingETagAnswers304WithTheNewServerDate() throws Exception {
        stored(CampaignStatus.PUBLISHED, 1, days(-1), days(5));
        String etag = visit().getHeader(HttpHeaders.ETAG);
        clock.advance(Duration.ofSeconds(30));

        for (String sent : List.of(etag, etag.substring(2), "\"outra\", " + etag)) {
            MockHttpServletResponse res = visit(sent);
            assertThat(res.getStatus()).as(sent).isEqualTo(304);
            assertThat(res.getContentAsByteArray()).isEmpty();
            assertThat(res.getHeader(HttpHeaders.ETAG)).isEqualTo(etag);
            assertThat(res.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("max-age=60, public");
            assertThat(res.getHeader(HttpHeaders.DATE)).isEqualTo(httpDate(NOW.plusSeconds(30)));
        }
        assertThat(visit("W/\"outra\"").getStatus()).isEqualTo(200);
    }

    @Test
    void theETagIgnoresTheServerTime_andSurvivesAReload() throws Exception {
        stored(CampaignStatus.PUBLISHED, 1, days(-1), days(5));
        MockHttpServletResponse first = visit();
        clock.advance(Duration.ofMinutes(3)); // a entrada venceu: é lida de novo, com o mesmo conteúdo

        MockHttpServletResponse later = visit();

        assertThat(later.getHeader(HttpHeaders.ETAG)).isEqualTo(first.getHeader(HttpHeaders.ETAG));
        assertThat(body(later).path("serverTime").asText()).isEqualTo(NOW.plus(3, ChronoUnit.MINUTES).toString());
    }

    // ───────────────────────────── invalidação ─────────────────────────────

    @Test
    void whatTheAdminDoesShowsUpImmediately() throws Exception {
        Map<String, Object> input = complete("Black Friday");
        input.put("startsAt", days(-1).toString());
        input.put("endsAt", days(5).toString());
        String id = create(input).body().path("id").asText();
        String empty = visit().getHeader(HttpHeaders.ETAG);

        action(id, "publish");
        MockHttpServletResponse published = visit(empty);
        assertThat(published.getStatus()).isEqualTo(200);
        assertThat(shownId(published)).isEqualTo(id);

        Map<String, Object> change = edit(id, "Black Friday");
        change.put("content", Map.of("topBar", Map.of("text", "Texto novo")));
        assertThat(put(id, change).status()).isEqualTo(200);
        MockHttpServletResponse edited = visit(published.getHeader(HttpHeaders.ETAG));
        assertThat(edited.getStatus()).isEqualTo(200);
        assertThat(body(edited).path("campaign").path("content").path("topBar").path("text").asText())
                .isEqualTo("Texto novo");

        action(id, "disable");
        assertThat(body(visit()).get("campaign").isNull()).isTrue();

        action(id, "reactivate");
        assertThat(shownId(visit())).isEqualTo(id);
    }

    @Test
    void deactivatingAndReactivatingAProductShowsUpImmediately() throws Exception {
        UUID product = product();
        Campaign campaign = CampaignFixtures.complete();
        campaign.setStatus(CampaignStatus.PUBLISHED);
        campaign.setStartsAt(days(-1));
        campaign.setEndsAt(days(5));
        campaign.addCollection(collection("ofertas", 0, product));
        campaigns.save(campaign);
        storeCache.invalidate();
        assertThat(shownProducts()).hasSize(1);

        assertThat(call("DELETE", "/api/product/" + product, admin.token(), null, Map.of()).status()).isEqualTo(200);
        assertThat(shownProducts()).isEmpty();

        long version = products.findById(product).orElseThrow().getVersion();
        assertThat(call("PATCH", "/api/product/" + product, admin.token(),
                Map.of("active", true, "version", version), Map.of()).status()).isEqualTo(200);
        assertThat(shownProducts()).hasSize(1);
    }

    @Test
    void aChangeMadeOutsideTheApiShowsUpWithinAMinute() throws Exception {
        assertThat(body(visit()).get("campaign").isNull()).isTrue();
        String id = stored(CampaignStatus.PUBLISHED, 1, days(-1), days(5)); // sem passar pelos serviços

        clock.set(NOW.plusSeconds(60).minus(1, ChronoUnit.MICROS));
        assertThat(body(visit()).get("campaign").isNull()).isTrue();

        clock.set(NOW.plusSeconds(60));
        assertThat(shownId(visit())).isEqualTo(id);
    }

    @Test
    void aClockSetBackDoesNotKeepAnOldAnswer() throws Exception {
        visit();
        String id = stored(CampaignStatus.PUBLISHED, 1, days(-1), days(5));

        clock.set(NOW.minusSeconds(10));

        assertThat(shownId(visit())).isEqualTo(id);
    }

    // ───────────────────────────── início e fim ─────────────────────────────

    @Test
    void theCacheRespectsTheStartAndTheEnd() throws Exception {
        Instant start = NOW.plusSeconds(30);
        Instant end = NOW.plusSeconds(90);
        String id = stored(CampaignStatus.PUBLISHED, 1, start, end);

        assertVisit(NOW, null, "max-age=30, public");
        assertVisit(start.minus(1, ChronoUnit.MICROS), null, "max-age=0, public");
        assertVisit(start, id, "max-age=60, public");
        assertVisit(end.minusSeconds(1), id, "max-age=1, public");
        assertVisit(end, null, "max-age=60, public");
    }

    // ───────────────────────────── helpers ─────────────────────────────

    private void assertVisit(Instant at, String campaign, String cacheControl) throws Exception {
        clock.set(at);
        MockHttpServletResponse res = visit();

        assertThat(shownId(res)).as("campanha em %s", at).isEqualTo(campaign);
        assertThat(res.getHeader(HttpHeaders.CACHE_CONTROL)).as("cache em %s", at).isEqualTo(cacheControl);
    }

    private MockHttpServletResponse visit() throws Exception {
        return mvc.perform(MockMvcRequestBuilders.get(ACTIVE)).andReturn().getResponse();
    }

    private MockHttpServletResponse visit(String ifNoneMatch) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.get(ACTIVE).header(HttpHeaders.IF_NONE_MATCH, ifNoneMatch))
                .andReturn().getResponse();
    }

    private JsonNode body(MockHttpServletResponse response) throws Exception {
        return json.readTree(response.getContentAsString(StandardCharsets.UTF_8));
    }

    private String shownId(MockHttpServletResponse response) throws Exception {
        return body(response).path("campaign").path("id").asText(null);
    }

    private JsonNode shownProducts() throws Exception {
        return body(visit()).path("campaign").path("collections").path(0).path("productIds");
    }

    private void action(String id, String action) throws Exception {
        assertThat(call("POST", BASE + "/" + id + "/" + action, admin.token(), Map.of("version", version(id)),
                Map.of()).status()).as(action).isEqualTo(200);
    }

    private static String httpDate(Instant instant) {
        return DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
                .withZone(ZoneOffset.UTC).format(instant);
    }

    private UUID product() {
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
        return products.save(p).getId();
    }
}
