package com.example.fausto_importados_api.audit;

import com.example.fausto_importados_api.model.Product;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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

    /** Só o que mudou: {campo: {from, to}}. Um campo presente em um só dos lados vale nulo no outro. */
    public static Map<String, Object> diff(Map<String, Object> before, Map<String, Object> after) {
        Set<String> fields = new LinkedHashSet<>(before.keySet());
        fields.addAll(after.keySet());
        Map<String, Object> changes = new LinkedHashMap<>();
        for (String field : fields) {
            Object previous = before.get(field);
            Object current = after.get(field);
            if (!Objects.equals(previous, current)) {
                Map<String, Object> change = new LinkedHashMap<>();
                change.put("from", previous);
                change.put("to", current);
                changes.put(field, change);
            }
        }
        return changes;
    }
}
