package com.example.fausto_importados_api.concurrency;

import com.example.fausto_importados_api.model.Product;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Idempotency-Key: a mesma chave repetida devolve a resposta original e não executa de novo. */
class IdempotencyConcurrencyTest extends ConcurrencyTestSupport {

    @Test
    void aDoubleClickOnCreateOrderCreatesOneOrder() {
        Product product = newProduct(5);
        Map<String, Object> body = orderBody(List.of(item(product, 1)));
        Map<String, String> headers = Map.of("Idempotency-Key", UUID.randomUUID().toString(), "X-Forwarded-For", freshIp());

        List<Res> results = concurrently(25, i -> () -> call("POST", "/api/orders", null, body, headers));

        assertThat(statuses(results)).containsOnly(200);
        assertThat(results.stream().map(r -> r.body().get("id").asText()).distinct()).hasSize(1);
        assertThat(ordersFor(product)).isEqualTo(1);
        assertThat(results.stream().filter(r -> r.headers().firstValue("Idempotent-Replayed").isPresent())).hasSize(24);
    }

    @Test
    void theSameKeyWithAnotherBodyIsRefused() {
        Product product = newProduct(5);
        Map<String, String> headers = Map.of("Idempotency-Key", UUID.randomUUID().toString(), "X-Forwarded-For", freshIp());

        assertThat(call("POST", "/api/orders", null, orderBody(List.of(item(product, 1))), headers).status()).isEqualTo(200);
        Res other = call("POST", "/api/orders", null, orderBody(List.of(item(product, 2))), headers);

        assertThat(other.status()).isEqualTo(422);
        assertThat(ordersFor(product)).isEqualTo(1);
    }

    @Test
    void aMalformedKeyIsRefused() {
        Res res = call("POST", "/api/orders", null, orderBody(List.of(item(newProduct(5), 1))),
                Map.of("Idempotency-Key", "curta", "X-Forwarded-For", freshIp()));

        assertThat(res.status()).isEqualTo(400);
    }

    @Test
    void aDoubleClickOnCompleteReturnsTheSameAnswerAndSellsOnce() {
        Session admin = admin();
        Product product = newProduct(10);
        String order = placeOrder(product, 3);
        Map<String, String> headers = Map.of("Idempotency-Key", UUID.randomUUID().toString());

        List<Res> results = concurrently(25,
                i -> () -> call("POST", "/api/orders/" + order + "/complete", admin, null, headers));

        assertThat(statuses(results)).containsOnly(200);
        assertThat(results.stream().map(Res::body).distinct()).hasSize(1);
        assertThat(stockOf(product)).isEqualTo(7);
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action = 'ORDER_COMPLETED' and entity_id = ?",
                Integer.class, order)).isEqualTo(1);
    }

    @Test
    void aFailedAttemptIsNotRemembered() {
        Session admin = admin();
        Product product = newProduct(0);
        String order = placeOrder(product, 1);
        Map<String, String> headers = Map.of("Idempotency-Key", UUID.randomUUID().toString());

        assertThat(call("POST", "/api/orders/" + order + "/complete", admin, null, headers).status()).isEqualTo(409);
        jdbc.update("update products set stock_quantity = 1 where id = ?", product.getId());

        assertThat(call("POST", "/api/orders/" + order + "/complete", admin, null, headers).status()).isEqualTo(200);
    }

    @Test
    void theSameKeyOnAnotherOrderIsAnotherOperation() {
        Session admin = admin();
        Product product = newProduct(10);
        String first = placeOrder(product, 1);
        String second = placeOrder(product, 1);
        Map<String, String> headers = Map.of("Idempotency-Key", UUID.randomUUID().toString());

        assertThat(call("POST", "/api/orders/" + first + "/complete", admin, null, headers).status()).isEqualTo(200);
        assertThat(call("POST", "/api/orders/" + second + "/complete", admin, null, headers).status()).isEqualTo(200);

        assertThat(stockOf(product)).isEqualTo(8);
    }

    @Test
    void aRepeatedStockDecreaseRunsOnce() {
        Session admin = admin();
        Product product = newProduct(10);
        Map<String, String> headers = Map.of("Idempotency-Key", UUID.randomUUID().toString());
        Object body = List.of(Map.of("productId", product.getId().toString(), "quantity", 2));

        List<Res> results = concurrently(15,
                i -> () -> call("POST", "/api/product/decrease-stock", admin, body, headers));

        assertThat(statuses(results)).containsOnly(200);
        assertThat(stockOf(product)).isEqualTo(8);
    }

    @Test
    void aRepeatedAdminCreationRunsOnce_andTheTemporaryPasswordIsNeverStored() {
        Session boss = admin();
        String email = "idem-" + UUID.randomUUID() + "@test.com";
        Map<String, String> headers = Map.of("Idempotency-Key", UUID.randomUUID().toString());

        List<Res> results = concurrently(10,
                i -> () -> call("POST", "/api/user", boss, Map.of("email", email), headers));

        assertThat(statuses(results)).containsOnly(201);
        assertThat(results.stream().filter(r -> r.body().hasNonNull("temporaryPassword"))).hasSize(1);
        assertThat(results.stream().map(r -> r.body().get("id").asText()).distinct()).hasSize(1);
        String temporary = results.stream().filter(r -> r.body().hasNonNull("temporaryPassword"))
                .findFirst().orElseThrow().body().get("temporaryPassword").asText();
        assertThat(jdbc.queryForList("select response_body from idempotency_keys where response_body is not null",
                String.class)).noneMatch(stored -> stored.contains(temporary));
    }

    private int ordersFor(Product product) {
        return jdbc.queryForObject("select count(distinct order_id) from order_items where product_id = ?",
                Integer.class, product.getId());
    }
}
