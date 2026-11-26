package com.example.fausto_importados_api.concurrency;

import com.example.fausto_importados_api.model.Product;
import com.example.fausto_importados_api.model.User;
import com.example.fausto_importados_api.model.enums.Category;
import com.example.fausto_importados_api.model.enums.Role;
import com.example.fausto_importados_api.repository.ProductRepository;
import com.example.fausto_importados_api.repository.UserRepository;
import com.example.fausto_importados_api.security.jwt.JwtService;
import com.example.fausto_importados_api.support.TestPostgres;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Base dos testes de concorrência: a API de verdade (HTTP numa porta aleatória) sobre um PostgreSQL de verdade.
 *
 * <p>Duas técnicas:
 * <ul>
 *   <li>{@link #concurrently}: dezenas de requisições liberadas no mesmo instante;</li>
 *   <li>{@link #whileLocked} e {@link #slowDown}: alargam a janela da corrida de propósito, para que o teste
 *       falhe de forma determinística quando a proteção não existe.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
abstract class ConcurrencyTestSupport {

    static final String PASSWORD = "senha-de-teste-123456";
    private static final AtomicInteger IPS = new AtomicInteger();

    @BeforeAll
    static void database() {
        TestPostgres.startOrSkip();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        TestPostgres.register(registry);
    }

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired ObjectMapper json;
    @Autowired UserRepository users;
    @Autowired ProductRepository products;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JwtService jwtService;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @BeforeEach
    void clearThrottle() {
        jdbc.update("delete from auth_throttle");
        jdbc.update("update users set active = true where email = 'admin@test.com'");
    }

    // ───────────────────────────── HTTP ─────────────────────────────

    record Res(int status, JsonNode body, java.net.http.HttpHeaders headers) {
    }

    record Session(User user, String access, String refreshCookie) {
        UUID id() {
            return user.getId();
        }
    }

    Res call(String method, String path, Session session, Object payload, Map<String, String> headers) {
        try {
            HttpRequest.BodyPublisher publisher = HttpRequest.BodyPublishers.noBody();
            Map<String, String> all = new LinkedHashMap<>();
            if (payload != null) {
                publisher = HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload));
                all.put("Content-Type", "application/json");
            }
            if (session != null) {
                all.put("Authorization", "Bearer " + session.access());
            }
            all.putAll(headers);
            return send(method, path, publisher, all);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    Res call(String method, String path, Session session, Object payload) {
        return call(method, path, session, payload, Map.of());
    }

    Res send(String method, String path, HttpRequest.BodyPublisher body, Map<String, String> headers) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .timeout(Duration.ofSeconds(60))
                    .method(method, body);
            headers.forEach(request::header);
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            String text = response.body();
            JsonNode node = text == null || text.isBlank() || !text.stripLeading().matches("(?s)^[\\[{].*")
                    ? json.createObjectNode() : json.readTree(text);
            return new Res(response.statusCode(), node, response.headers());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    Res refresh(String cookie) {
        return call("POST", "/auth/refresh", null, null, Map.of(
                "Origin", "http://localhost:5173",
                "X-Requested-With", "fausto-web",
                "Cookie", "refresh_token=" + cookie));
    }

    static String freshIp() {
        int n = IPS.incrementAndGet();
        return "10.77." + (n / 250) + "." + (n % 250 + 1);
    }

    // ───────────────────────────── dados ─────────────────────────────

    User newUser(Role role) {
        User u = new User();
        u.setEmail("conc-" + UUID.randomUUID() + "@test.com");
        u.setPassword(passwordEncoder.encode(PASSWORD));
        u.setRole(role);
        u.setActive(true);
        return users.save(u);
    }

    Session signIn(User user) {
        Res res = call("POST", "/auth/login", null, Map.of("email", user.getEmail(), "password", PASSWORD),
                Map.of("X-Forwarded-For", freshIp()));
        assertThat(res.status()).as("login").isEqualTo(200);
        String cookie = res.headers().allValues("Set-Cookie").stream()
                .filter(h -> h.startsWith("refresh_token="))
                .filter(h -> !h.startsWith("refresh_token=;"))
                .findFirst().orElseThrow();
        return new Session(user, res.body().get("accessToken").asText(),
                cookie.substring("refresh_token=".length(), cookie.indexOf(';')));
    }

    Session admin() {
        return signIn(newUser(Role.ADMIN));
    }

    Product newProduct(int stock) {
        Product p = new Product();
        p.setName("Perfume " + UUID.randomUUID());
        p.setBrand("Marca");
        p.setDescription("desc");
        p.setOlfactiveFamily("FLORAL");
        p.setCategory(Category.UNISSEX);
        p.setSize("100");
        p.setPrice(new BigDecimal("100.00"));
        p.setImage("http://img/x.png");
        p.setFeatured(false);
        p.setInStock(stock > 0);
        p.setStockQuantity(stock);
        p.setActive(true);
        return products.save(p);
    }

    int stockOf(Product product) {
        return jdbc.queryForObject("select stock_quantity from products where id = ?", Integer.class, product.getId());
    }

    String orderStatus(String orderId) {
        return jdbc.queryForObject("select status from orders where id = ?::uuid", String.class, orderId);
    }

    static Map<String, Object> item(Product product, int quantity) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("productId", product.getId().toString());
        item.put("productName", product.getName());
        item.put("productSize", product.getSize());
        item.put("productCategory", "UNISSEX");
        item.put("productFamily", "FLORAL");
        item.put("onSale", false);
        item.put("quantity", quantity);
        item.put("unitPrice", product.getPrice());
        return item;
    }

    static Map<String, Object> orderBody(List<Map<String, Object>> items) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("customerName", "Cliente");
        body.put("customerWhatsapp", "11999999999");
        body.put("paymentMethod", "pix");
        body.put("total", 100);
        body.put("items", items);
        return body;
    }

    String placeOrder(Product product, int quantity) {
        Res res = call("POST", "/api/orders", null, orderBody(List.of(item(product, quantity))),
                Map.of("X-Forwarded-For", freshIp()));
        assertThat(res.status()).as("criar pedido").isEqualTo(200);
        return res.body().get("id").asText();
    }

    // ───────────────────────────── concorrência ─────────────────────────────

    /** Executa {@code threads} tarefas liberadas no mesmo instante. */
    static <T> List<T> concurrently(int threads, IntFunction<Callable<T>> tasks) {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                Callable<T> task = tasks.apply(i);
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return task.call();
                }));
            }
            ready.await();
            go.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                results.add(f.get(90, TimeUnit.SECONDS));
            }
            return results;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            pool.shutdownNow();
        }
    }

    static List<Integer> statuses(List<Res> results) {
        return results.stream().map(Res::status).toList();
    }

    static long countStatus(List<Res> results, int status) {
        return results.stream().filter(r -> r.status() == status).count();
    }

    /**
     * "Outro escritor" determinístico: trava a linha, dispara a requisição, espera ela parar no lock, faz a
     * alteração concorrente, confirma e devolve a resposta da requisição.
     */
    Res whileLocked(String lockSql, Callable<Res> request, List<String> concurrentChanges) {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection other = dataSource.getConnection()) {
            other.setAutoCommit(false);
            try (Statement statement = other.createStatement()) {
                statement.execute(lockSql);
                Future<Res> response = pool.submit(request);
                awaitBlockedBackend(response);
                for (String sql : concurrentChanges) {
                    statement.execute(sql);
                }
                other.commit();
                return response.get(60, TimeUnit.SECONDS);
            } finally {
                other.rollback();
                other.setAutoCommit(true);
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            pool.shutdownNow();
        }
    }

    private void awaitBlockedBackend(Future<Res> response) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (System.nanoTime() < deadline && !response.isDone()) {
            Integer waiting = jdbc.queryForObject(
                    "select count(*) from pg_stat_activity where datname = current_database() and wait_event_type = 'Lock'",
                    Integer.class);
            if (waiting != null && waiting > 0) {
                return;
            }
            Thread.sleep(20);
        }
    }

    /** Faz cada escrita na tabela demorar, alargando a janela entre "ler" e "confirmar". Fechar remove. */
    AutoCloseable slowDown(String table, String event, double seconds) {
        jdbc.execute("create or replace function test_slow() returns trigger language plpgsql as "
                + "$$ begin perform pg_sleep(" + seconds + "); return null; end $$");
        jdbc.execute("create trigger test_slow after " + event + " on " + table
                + " for each row execute function test_slow()");
        return () -> jdbc.execute("drop trigger if exists test_slow on " + table);
    }

    static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
