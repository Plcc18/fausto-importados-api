package com.example.fausto_importados_api.concurrency;

import com.example.fausto_importados_api.model.Product;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.http.HttpRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Edição de produtos pelo painel concorrendo com vendas e com outras edições. */
class ProductEditConcurrencyTest extends ConcurrencyTestSupport {

    @Test
    void theAdminSeesVersionAndStock_anonymousVisitorsDoNot() {
        Session admin = admin();
        Product product = newProduct(7);

        Res asAdmin = call("GET", "/api/product/" + product.getId(), admin, null);
        Res anonymous = call("GET", "/api/product/" + product.getId(), null, null, Map.of("X-Forwarded-For", freshIp()));

        assertThat(asAdmin.body().path("stockQuantity").asInt(-1)).isEqualTo(7);
        assertThat(asAdmin.body().has("version")).isTrue();
        assertThat(anonymous.body().has("stockQuantity")).isFalse();
        assertThat(anonymous.body().has("version")).isFalse();
    }

    @Test
    void aPatchWithAStaleVersionIsRefused_andTheSaleSurvives() {
        Session admin = admin();
        Product product = newProduct(10);
        long seen = versionOf(product);
        // uma venda acontece depois que o admin abriu o formulário
        assertThat(sell(admin, product, 4).status()).isEqualTo(200);

        Res res = call("PATCH", "/api/product/" + product.getId(), admin, Map.of("stockQuantity", 10, "version", seen));

        assertThat(res.status()).isEqualTo(409);
        assertThat(res.body().path("code").asText()).isEqualTo("version_conflict");
        assertThat(stockOf(product)).isEqualTo(6);
    }

    @Test
    void aPutWithAStaleVersionIsRefused_andTheSaleSurvives() {
        Session admin = admin();
        Product product = newProduct(10);
        long seen = versionOf(product);
        assertThat(sell(admin, product, 4).status()).isEqualTo(200);

        Res res = put(admin, product, 10, seen);

        assertThat(res.status()).isEqualTo(409);
        assertThat(stockOf(product)).isEqualTo(6);
    }

    @Test
    void aPutWithTheCurrentVersionIsAccepted() {
        Session admin = admin();
        Product product = newProduct(10);

        Res res = put(admin, product, 25, versionOf(product));

        assertThat(res.status()).isEqualTo(200);
        assertThat(stockOf(product)).isEqualTo(25);
    }

    @Test
    void onlyOneOfManyEditsOfTheSameVersionWins() {
        Session admin = admin();
        Product product = newProduct(10);
        long seen = versionOf(product);

        List<Res> results = concurrently(20, i -> () -> call("PATCH", "/api/product/" + product.getId(), admin,
                Map.of("price", 200 + i, "version", seen)));

        assertThat(countStatus(results, 200)).as(statuses(results).toString()).isEqualTo(1);
        assertThat(countStatus(results, 409)).isEqualTo(19);
    }

    @Test
    void anEditThatDoesNotTouchStockNeverRestoresAnOldStock_evenWithoutVersion() {
        Session admin = admin();
        Product product = newProduct(10);

        Res res = whileLocked(
                "select 1 from products where id = '" + product.getId() + "' for update",
                () -> call("PATCH", "/api/product/" + product.getId(), admin, Map.of("price", 150)),
                List.of("update products set stock_quantity = 3 where id = '" + product.getId() + "'"));

        assertThat(res.status()).isEqualTo(200);
        assertThat(stockOf(product)).isEqualTo(3);
        assertThat(jdbc.queryForObject("select price from products where id = ?", BigDecimal.class, product.getId()))
                .isEqualByComparingTo("150");
    }

    @Test
    void theSameProductCreatedManyTimesAtOnceExistsOnce() {
        Session admin = admin();
        Map<String, Object> body = productBody("Duplicado " + UUID.randomUUID(), "100", 5);

        List<Res> results = concurrently(20, i -> () -> call("POST", "/api/product", admin, body));

        assertThat(countStatus(results, 201)).as(statuses(results).toString()).isEqualTo(1);
        assertThat(countStatus(results, 409)).isEqualTo(19);
        assertThat(jdbc.queryForObject("select count(*) from products where name = ?", Integer.class, body.get("name")))
                .isEqualTo(1);
    }

    @Test
    void theSameNameInAnotherSizeIsADifferentProduct() {
        Session admin = admin();
        String name = "Tamanhos " + UUID.randomUUID();

        assertThat(call("POST", "/api/product", admin, productBody(name, "50", 1)).status()).isEqualTo(201);
        assertThat(call("POST", "/api/product", admin, productBody(name, "100", 1)).status()).isEqualTo(201);
        assertThat(call("POST", "/api/product", admin, productBody(name.toUpperCase(), "100", 1)).status()).isEqualTo(409);
    }

    @Test
    void renamingIntoAnExistingProductIsRefused() {
        Session admin = admin();
        Product first = newProduct(1);
        Product second = newProduct(1);

        Res res = call("PATCH", "/api/product/" + second.getId(), admin, Map.of("name", first.getName()));

        assertThat(res.status()).isEqualTo(409);
    }

    @Test
    void deletingAProductKeepsTheRowForTheOrderHistory() {
        Session admin = admin();
        Product product = newProduct(5);
        placeOrder(product, 1);

        assertThat(call("DELETE", "/api/product/" + product.getId(), admin, null).status()).isEqualTo(200);

        assertThat(jdbc.queryForObject("select active from products where id = ?", Boolean.class, product.getId())).isFalse();
        assertThat(call("GET", "/api/product/" + product.getId(), admin, null).status()).isEqualTo(404);
    }

    // ───────────────────────────── helpers ─────────────────────────────

    private long versionOf(Product product) {
        Res res = call("GET", "/api/product/" + product.getId(), admin(), null);
        return res.body().path("version").asLong(0);
    }

    private Res sell(Session admin, Product product, int quantity) {
        String order = placeOrder(product, quantity);
        return call("POST", "/api/orders/" + order + "/complete", admin, null);
    }

    private static Map<String, Object> productBody(String name, String size, int stock) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("brand", "Marca");
        body.put("description", "desc");
        body.put("olfactiveFamily", "FLORAL");
        body.put("category", "UNISSEX");
        body.put("size", size);
        body.put("price", 199.90);
        body.put("image", "http://img/x.png");
        body.put("inStock", true);
        body.put("stockQuantity", stock);
        return body;
    }

    private Res put(Session admin, Product product, int stock, long version) {
        Map<String, Object> body = productBody(product.getName(), product.getSize(), stock);
        body.put("version", version);
        String boundary = "----teste" + UUID.randomUUID();
        try {
            String multipart = "--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"product\"\r\n\r\n"
                    + json.writeValueAsString(body) + "\r\n"
                    + "--" + boundary + "--\r\n";
            return send("PUT", "/api/product/" + product.getId(), HttpRequest.BodyPublishers.ofString(multipart), Map.of(
                    "Authorization", "Bearer " + admin.access(),
                    "Content-Type", "multipart/form-data; boundary=" + boundary));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
