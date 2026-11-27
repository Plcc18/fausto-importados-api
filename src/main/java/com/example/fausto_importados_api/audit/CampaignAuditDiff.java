package com.example.fausto_importados_api.audit;

import com.example.fausto_importados_api.model.Campaign;
import com.example.fausto_importados_api.model.CampaignCollection;
import com.example.fausto_importados_api.model.CampaignProduct;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * "Antes e depois" de campanhas para a auditoria. Cada campo é identificado pelo caminho no JSON do contrato (ex.:
 * {@code theme.dark.primary}, {@code content.hero.buttons[0].label}), para o registro mostrar só o que mudou. As
 * coleções são comparadas pelo id, em {@code collections.added}, {@code removed} e {@code changed}.
 */
@Component
public class CampaignAuditDiff {

    /** Produtos citados por id em cada mudança; a contagem é sempre a total. */
    private static final int MAX_LISTED_PRODUCTS = 20;

    /** Cópia do que a campanha tinha num momento: alterar a campanha depois não a altera. */
    public record Snapshot(Map<String, Object> fields, Map<UUID, CollectionSnapshot> collections) {

        /** Antes de existir, ou depois de excluída. */
        public static Snapshot empty() {
            return new Snapshot(Map.of(), Map.of());
        }
    }

    public record CollectionSnapshot(String title, String slug, int position, List<UUID> products) {
    }

    private final ObjectMapper json;

    public CampaignAuditDiff(ObjectMapper json) {
        this.json = json;
    }

    /** Chamar dentro da transação: lê as coleções e os produtos. */
    public Snapshot snapshot(Campaign campaign) {
        Map<String, Object> fields = new LinkedHashMap<>();
        put(fields, "name", campaign.getName());
        put(fields, "description", campaign.getDescription());
        put(fields, "type", campaign.getType());
        put(fields, "status", campaign.getStatus());
        put(fields, "priority", campaign.getPriority());
        put(fields, "startsAt", campaign.getStartsAt());
        put(fields, "endsAt", campaign.getEndsAt());
        put(fields, "publishedAt", campaign.getPublishedAt());
        flatten("theme", json.valueToTree(campaign.getTheme()), fields);
        flatten("content", json.valueToTree(campaign.getContent()), fields);

        Map<UUID, CollectionSnapshot> collections = new LinkedHashMap<>();
        campaign.getCollections().stream()
                .sorted(Comparator.comparingInt(CampaignCollection::getPosition))
                .forEach(c -> collections.put(c.getId(), new CollectionSnapshot(
                        c.getTitle(), c.getSlug(), c.getPosition(),
                        c.getProducts().stream()
                                .sorted(Comparator.comparingInt(CampaignProduct::position))
                                .map(CampaignProduct::productId)
                                .toList())));
        return new Snapshot(fields, collections);
    }

    /** Só o que mudou: {@code {campo: {from, to}}}, mais {@code collections} se alguma mudou. */
    public Map<String, Object> changes(Snapshot before, Snapshot after) {
        Map<String, Object> changes = AuditDiff.diff(before.fields(), after.fields());
        Map<String, Object> collections = collectionChanges(before.collections(), after.collections());
        if (!collections.isEmpty()) {
            changes.put("collections", collections);
        }
        return changes;
    }

    // ───────────────────────────── campos ─────────────────────────────

    private static void put(Map<String, Object> fields, String name, Object value) {
        if (value != null) {
            fields.put(name, value instanceof Number ? value : value.toString());
        }
    }

    // só as folhas com valor: o que falta aparece como "null" na comparação
    private void flatten(String path, JsonNode node, Map<String, Object> into) {
        if (node == null || node.isNull() || (node.isArray() && node.isEmpty())) {
            return;
        }
        if (node.isObject()) {
            node.properties().forEach(entry -> flatten(path + "." + entry.getKey(), entry.getValue(), into));
        } else if (node.isArray() && node.get(0).isContainerNode()) {
            for (int i = 0; i < node.size(); i++) {
                flatten(path + "[" + i + "]", node.get(i), into);
            }
        } else {
            into.put(path, json.convertValue(node, Object.class));
        }
    }

    // ───────────────────────────── coleções ─────────────────────────────

    private static Map<String, Object> collectionChanges(
            Map<UUID, CollectionSnapshot> before, Map<UUID, CollectionSnapshot> after
    ) {
        List<Object> added = new ArrayList<>();
        List<Object> removed = new ArrayList<>();
        List<Object> changed = new ArrayList<>();
        after.forEach((id, current) -> {
            CollectionSnapshot previous = before.get(id);
            if (previous == null) {
                added.add(summary(current));
            } else if (!previous.equals(current)) {
                changed.add(describe(previous, current));
            }
        });
        before.forEach((id, previous) -> {
            if (!after.containsKey(id)) {
                removed.add(summary(previous));
            }
        });

        Map<String, Object> result = new LinkedHashMap<>();
        if (!added.isEmpty()) {
            result.put("added", added);
        }
        if (!removed.isEmpty()) {
            result.put("removed", removed);
        }
        if (!changed.isEmpty()) {
            result.put("changed", changed);
        }
        return result;
    }

    private static Map<String, Object> summary(CollectionSnapshot collection) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("slug", collection.slug());
        summary.put("title", collection.title());
        summary.put("products", collection.products().size());
        return summary;
    }

    private static Map<String, Object> describe(CollectionSnapshot before, CollectionSnapshot after) {
        Map<String, Object> description = new LinkedHashMap<>();
        description.put("slug", after.slug()); // para saber de qual se trata
        Map<String, Object> changes = AuditDiff.diff(plain(before), plain(after));
        if (!changes.isEmpty()) {
            description.put("changes", changes);
        }
        List<UUID> added = without(after.products(), before.products());
        List<UUID> removed = without(before.products(), after.products());
        if (!added.isEmpty()) {
            description.put("productsAdded", listing(added));
        }
        if (!removed.isEmpty()) {
            description.put("productsRemoved", listing(removed));
        }
        // a ordem dos que ficaram, sem contar quem entrou ou saiu
        if (!without(before.products(), removed).equals(without(after.products(), added))) {
            description.put("productsReordered", true);
        }
        return description;
    }

    private static Map<String, Object> plain(CollectionSnapshot collection) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("title", collection.title());
        fields.put("slug", collection.slug());
        fields.put("position", collection.position());
        return fields;
    }

    private static List<UUID> without(List<UUID> items, List<UUID> excluded) {
        return items.stream().filter(item -> !excluded.contains(item)).toList();
    }

    private static Map<String, Object> listing(List<UUID> ids) {
        Map<String, Object> listing = new LinkedHashMap<>();
        listing.put("count", ids.size());
        listing.put("ids", ids.stream().limit(MAX_LISTED_PRODUCTS).map(UUID::toString).toList());
        return listing;
    }
}
