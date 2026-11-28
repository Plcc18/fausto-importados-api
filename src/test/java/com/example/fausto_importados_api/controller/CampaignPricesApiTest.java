package com.example.fausto_importados_api.controller;

import com.example.fausto_importados_api.model.Product;
import com.example.fausto_importados_api.model.enums.Category;
import com.example.fausto_importados_api.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T35: campanha só destaca produtos. Criar, publicar, editar (antes e durante o ar), ativar, desativar, reativar,
 * duplicar, encerrar pelo calendário e arquivar a campanha com os produtos, e excluir a cópia (só rascunhos são
 * excluídos), nunca alteram {@code price}, {@code originalPrice} nem a versão dos produtos participantes
 * (CLAUDE.md: "ativar, encerrar ou editar uma campanha nunca altera os preços cadastrados dos produtos").
 */
class CampaignPricesApiTest extends CampaignApiTestSupport {

    @Autowired ProductRepository products;

    /** Preço, preço original e versão: o que um pedido e a loja usam. */
    record Prices(BigDecimal price, BigDecimal originalPrice, Long version) {
    }

    @Test
    void campaignActionsNeverTouchProductPrices() throws Exception {
        UUID onSale = product(new BigDecimal("199.90"), new BigDecimal("249.90"));
        UUID regular = product(new BigDecimal("320.00"), null);
        Map<UUID, Prices> before = prices(onSale, regular);
        Map<String, String> reauth = Map.of("X-Reauth", requireSecondFactor());

        // Rascunho com os dois produtos numa coleção (o desconto da segunda etapa fica vazio)
        Map<String, Object> body = complete("Black Friday");
        body.put("startsAt", days(1).toString());
        body.put("endsAt", days(10).toString());
        body.put("collections", List.of(collection("ofertas", onSale, regular)));
        Res created = create(body);
        assertThat(created.status()).as("criar").isEqualTo(201);
        String id = created.body().path("id").asText();
        assertThat(prices(onSale, regular)).isEqualTo(before);

        assertOk(action(id, "publish", reauth), "publicar");
        assertThat(prices(onSale, regular)).isEqualTo(before);

        Map<String, Object> edited = edit(id, "Black Friday editada");
        assertThat(put(id, edited, reauth).status()).as("editar publicada").isEqualTo(200);
        assertThat(prices(onSale, regular)).isEqualTo(before);

        assertOk(action(id, "activate-now", reauth), "ativar agora");
        assertThat(prices(onSale, regular)).isEqualTo(before);
        assertThat(get("/api/campaigns/active").body().path("campaign").path("id").asText()).isEqualTo(id);

        Map<String, Object> live = edit(id, "Black Friday no ar");
        assertThat(put(id, live, reauth).status()).as("editar no ar").isEqualTo(200);
        assertThat(prices(onSale, regular)).isEqualTo(before);

        assertOk(action(id, "disable", Map.of()), "desativar");
        assertThat(prices(onSale, regular)).isEqualTo(before);

        assertOk(action(id, "reactivate", reauth), "reativar");
        assertThat(prices(onSale, regular)).isEqualTo(before);

        Res copy = call("POST", BASE + "/" + id + "/duplicate", admin.token(), null, Map.of("Idempotency-Key", newKey()));
        assertThat(copy.status()).as("duplicar").isEqualTo(201);
        assertThat(prices(onSale, regular)).isEqualTo(before);

        // Encerrar pelo calendário: a campanha sai do ar sozinha
        clock.set(days(11));
        storeCache.invalidate();
        assertThat(get("/api/campaigns/active").body().path("campaign").isNull()).isTrue();
        assertThat(prices(onSale, regular)).isEqualTo(before);

        // 11 dias depois o login do teste venceu: entra de novo
        admin = signIn(com.example.fausto_importados_api.model.enums.Role.ADMIN);
        Map<String, String> later = Map.of("X-Reauth", requireSecondFactor());
        assertOk(action(id, "archive", later), "arquivar");
        String draft = copy.body().path("id").asText();
        Res deleted = call("DELETE", BASE + "/" + draft + "?version=" + version(draft), admin.token(), null, later);
        assertThat(deleted.status()).as("excluir a cópia").isEqualTo(204);
        assertThat(prices(onSale, regular)).isEqualTo(before);
    }

    private void assertOk(Res res, String what) {
        assertThat(res.status()).as(what + ": " + res.body()).isEqualTo(200);
    }

    private Res action(String id, String action, Map<String, String> headers) throws Exception {
        return call("POST", BASE + "/" + id + "/" + action, admin.token(), Map.of("version", version(id)), headers);
    }

    private Res put(String id, Object body, Map<String, String> headers) throws Exception {
        return call("PUT", BASE + "/" + id, admin.token(), body, headers);
    }

    private Map<UUID, Prices> prices(UUID... ids) {
        Map<UUID, Prices> result = new LinkedHashMap<>();
        for (UUID id : ids) {
            Product p = products.findById(id).orElseThrow();
            result.put(id, new Prices(p.getPrice(), p.getOriginalPrice(), p.getVersion()));
        }
        return result;
    }

    private UUID product(BigDecimal price, BigDecimal originalPrice) {
        Product p = new Product();
        p.setName("Perfume " + UUID.randomUUID());
        p.setBrand("Marca");
        p.setDescription("Descrição");
        p.setCategory(Category.UNISSEX);
        p.setOlfactiveFamily("FLORAL");
        p.setSize("100ml");
        p.setPrice(price);
        p.setOriginalPrice(originalPrice);
        p.setImage("https://example.com/p.webp");
        p.setInStock(true);
        p.setStockQuantity(3);
        return products.save(p).getId();
    }

    private static Map<String, Object> collection(String slug, UUID... productIds) {
        Map<String, Object> collection = new LinkedHashMap<>();
        collection.put("title", "Ofertas Black Friday");
        collection.put("slug", slug);
        collection.put("products", java.util.Arrays.stream(productIds).map(id -> Map.of("productId", id)).toList());
        return collection;
    }
}
