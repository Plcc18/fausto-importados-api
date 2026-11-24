package com.example.fausto_importados_api.audit;

import com.example.fausto_importados_api.model.Product;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Monta os "antes/depois" de produtos para a auditoria (só campos de negócio; nada sensível existe aqui). */
public final class AuditDiff {

    private AuditDiff() {
    }

    /** Retrato dos campos de um produto relevantes para auditar alterações de preço, estoque e cadastro. */
    public static Map<String, Object> product(Product p) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("name", p.getName());
        snapshot.put("brand", p.getBrand());
        snapshot.put("description", p.getDescription());
        snapshot.put("olfactiveFamily", p.getOlfactiveFamily());
        snapshot.put("category", p.getCategory() == null ? null : p.getCategory().name());
        snapshot.put("size", p.getSize());
        snapshot.put("price", p.getPrice() == null ? null : p.getPrice().toPlainString());
        snapshot.put("originalPrice", p.getOriginalPrice() == null ? null : p.getOriginalPrice().toPlainString());
        snapshot.put("image", p.getImage());
        snapshot.put("featured", p.getFeatured());
        snapshot.put("inStock", p.getInStock());
        snapshot.put("stockQuantity", p.getStockQuantity());
        return snapshot;
    }

    /** Resumo de um produto recém-criado. */
    public static Map<String, Object> created(Product p) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("name", p.getName());
        summary.put("price", p.getPrice() == null ? null : p.getPrice().toPlainString());
        summary.put("stockQuantity", p.getStockQuantity());
        return summary;
    }

    /** Só o que mudou: {campo: {from, to}}. */
    public static Map<String, Object> diff(Map<String, Object> before, Map<String, Object> after) {
        Map<String, Object> changes = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : after.entrySet()) {
            Object previous = before.get(entry.getKey());
            if (!Objects.equals(previous, entry.getValue())) {
                Map<String, Object> change = new LinkedHashMap<>();
                change.put("from", previous);
                change.put("to", entry.getValue());
                changes.put(entry.getKey(), change);
            }
        }
        return changes;
    }
}
