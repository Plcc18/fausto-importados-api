package com.example.fausto_importados_api.concurrency;

import com.example.fausto_importados_api.model.Product;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** Estoque e pedidos sob concorrência. */
class OrderStockConcurrencyTest extends ConcurrencyTestSupport {

    @Test
    void theLastItemIsSoldOnlyOnce() {
        Session admin = admin();
        Product product = newProduct(1);
        List<String> orders = IntStream.range(0, 20).mapToObj(i -> placeOrder(product, 1)).toList();

        List<Res> results = concurrently(orders.size(),
                i -> () -> call("POST", "/api/orders/" + orders.get(i) + "/complete", admin, null));

        assertThat(countStatus(results, 200)).as(statuses(results).toString()).isEqualTo(1);
        assertThat(countStatus(results, 409)).isEqualTo(19);
        assertThat(stockOf(product)).isZero();
        assertThat(orders.stream().filter(id -> "COMPLETED".equals(orderStatus(id)))).hasSize(1);
        assertThat(jdbc.queryForObject("select in_stock from products where id = ?", Boolean.class, product.getId())).isFalse();
    }

    @Test
    void completingTheSameOrderManyTimesDecreasesTheStockOnce() {
        Session admin = admin();
        Product product = newProduct(10);
        String order = placeOrder(product, 2);

        List<Res> results = concurrently(25, i -> () -> call("POST", "/api/orders/" + order + "/complete", admin, null));

        assertThat(countStatus(results, 200)).as(statuses(results).toString()).isEqualTo(1);
        assertThat(countStatus(results, 409)).isEqualTo(24);
        assertThat(stockOf(product)).isEqualTo(8);
    }

    @Test
    void completeAndCancelOfTheSameOrderNeverBothWin() {
        Session admin = admin();
        Product product = newProduct(100);
        List<String> orders = IntStream.range(0, 20).mapToObj(i -> placeOrder(product, 1)).toList();

        List<Res> results = concurrently(orders.size() * 2, i -> () -> call(
                "POST", "/api/orders/" + orders.get(i / 2) + (i % 2 == 0 ? "/complete" : "/cancel"), admin, null));

        for (int i = 0; i < orders.size(); i++) {
            List<Res> pair = results.subList(i * 2, i * 2 + 2);
            assertThat(countStatus(pair, 200)).as("pedido %d: %s", i, statuses(pair)).isEqualTo(1);
            String expected = pair.get(0).status() == 200 ? "COMPLETED" : "CANCELLED";
            assertThat(orderStatus(orders.get(i))).isEqualTo(expected);
        }
        long completed = orders.stream().filter(id -> "COMPLETED".equals(orderStatus(id))).count();
        assertThat(stockOf(product)).isEqualTo(100 - (int) completed);
    }

    @Test
    void ordersSharingProductsInOppositeItemOrderAllComplete_withoutDeadlocks() {
        Session admin = admin();
        Product a = newProduct(100);
        Product b = newProduct(100);
        List<String> orders = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            List<Map<String, Object>> items = i % 2 == 0 ? List.of(item(a, 1), item(b, 2)) : List.of(item(b, 2), item(a, 1));
            Res res = call("POST", "/api/orders", null, orderBody(items), Map.of("X-Forwarded-For", freshIp()));
            assertThat(res.status()).isEqualTo(200);
            orders.add(res.body().get("id").asText());
        }

        List<Res> results = concurrently(orders.size(),
                i -> () -> call("POST", "/api/orders/" + orders.get(i) + "/complete", admin, null));

        assertThat(statuses(results)).containsOnly(200);
        assertThat(stockOf(a)).isEqualTo(70);
        assertThat(stockOf(b)).isEqualTo(40);
    }

    @Test
    void concurrentStockDecreasesNeverLoseAnUpdateNorGoNegative() {
        Session admin = admin();
        Product product = newProduct(30);

        List<Res> results = concurrently(40, i -> () -> call("POST", "/api/product/decrease-stock", admin,
                List.of(Map.of("productId", product.getId().toString(), "quantity", 1))));

        assertThat(countStatus(results, 200)).as(statuses(results).toString()).isEqualTo(30);
        assertThat(countStatus(results, 400)).isEqualTo(10);
        assertThat(stockOf(product)).isZero();
    }

    @Test
    void aStockDecreaseIsAllOrNothing() {
        Session admin = admin();
        Product enough = newProduct(5);
        Product notEnough = newProduct(1);

        Res res = call("POST", "/api/product/decrease-stock", admin, List.of(
                Map.of("productId", enough.getId().toString(), "quantity", 2),
                Map.of("productId", notEnough.getId().toString(), "quantity", 2)));

        assertThat(res.status()).isEqualTo(400);
        assertThat(stockOf(enough)).isEqualTo(5);
        assertThat(stockOf(notEnough)).isEqualTo(1);
    }

    @Test
    void aNegativeQuantityNeverIncreasesTheStock() {
        Session admin = admin();
        Product product = newProduct(5);

        Res decrease = call("POST", "/api/product/decrease-stock", admin,
                List.of(Map.of("productId", product.getId().toString(), "quantity", -3)));
        Res order = call("POST", "/api/orders", null, orderBody(List.of(item(product, -3))),
                Map.of("X-Forwarded-For", freshIp()));

        assertThat(decrease.status()).isEqualTo(400);
        assertThat(order.status()).isEqualTo(400);
        assertThat(stockOf(product)).isEqualTo(5);
    }

    @Test
    void theServerDecidesPricesAndTotal() {
        Product product = newProduct(5);
        Map<String, Object> tampered = item(product, 3);
        tampered.put("unitPrice", 0.01);
        tampered.put("productName", "outro nome");
        Map<String, Object> body = orderBody(List.of(tampered));
        body.put("total", 0.03);

        Res res = call("POST", "/api/orders", null, body, Map.of("X-Forwarded-For", freshIp()));

        assertThat(res.status()).isEqualTo(200);
        assertThat(new BigDecimal(res.body().get("total").asText())).isEqualByComparingTo("300.00");
        assertThat(new BigDecimal(res.body().get("items").get(0).get("unitPrice").asText())).isEqualByComparingTo("100.00");
        assertThat(res.body().get("items").get(0).get("productName").asText()).isEqualTo(product.getName());
    }

    @Test
    void everyOrderListingStillLoadsWithData() {
        Session admin = admin();
        Product product = newProduct(5);
        String completed = placeOrder(product, 1);
        placeOrder(product, 1);
        assertThat(call("POST", "/api/orders/" + completed + "/complete", admin, null).status()).isEqualTo(200);

        for (String path : List.of("/api/orders", "/api/orders/filter?status=PENDING", "/api/orders/report")) {
            Res res = call("GET", path, admin, null);
            assertThat(res.status()).as(path).isEqualTo(200);
            assertThat(res.body().get(0).get("items")).as(path).isNotEmpty();
        }
        assertThat(call("GET", "/api/orders/sales-stats", admin, null).status()).isEqualTo(200);
    }

    @Test
    void cancellingReturnsTheOrderWithItsItems() {
        Session admin = admin();
        String order = placeOrder(newProduct(5), 1);

        Res res = call("POST", "/api/orders/" + order + "/cancel", admin, null);

        assertThat(res.status()).isEqualTo(200);
        assertThat(res.body().get("items")).hasSize(1);
    }
}
