package com.example.fausto_importados_api.services;

import com.example.fausto_importados_api.dto.campaign.CollectionInput;
import com.example.fausto_importados_api.dto.validation.FieldChecks;
import com.example.fausto_importados_api.dto.validation.InvalidField;
import com.example.fausto_importados_api.exception.FieldValidationException;
import com.example.fausto_importados_api.repository.ProductRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

/** Confere as coleções de uma campanha e os produtos delas (contrato da API, §4.3). */
@Component
public class CampaignCollectionValidator {

    private static final int MAX_COLLECTIONS = 8;
    private static final int MAX_PRODUCTS = 60;
    private static final int MAX_TITLE = 60;
    private static final int MAX_SLUG = 40;
    private static final Pattern SLUG = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");

    private final ProductRepository products;

    public CampaignCollectionValidator(ProductRepository products) {
        this.products = products;
    }

    /**
     * Desconto é da segunda etapa. Recusar, em vez de ignorar, evita que o admin ache que ele foi aplicado.
     *
     * @throws FieldValidationException 400 {@code discount_not_supported}
     */
    public void rejectDiscounts(List<CollectionInput> collections) {
        List<InvalidField> found = new ArrayList<>();
        for (int i = 0; collections != null && i < collections.size(); i++) {
            List<CollectionInput.Product> items = collections.get(i) == null ? null : collections.get(i).products();
            for (int j = 0; items != null && j < items.size(); j++) {
                if (items.get(j) != null && items.get(j).discount() != null) {
                    found.add(new InvalidField("collections[" + i + "].products[" + j + "].discount",
                            "Descontos ainda não estão disponíveis."));
                }
            }
        }
        if (!found.isEmpty()) {
            throw new FieldValidationException(HttpStatus.BAD_REQUEST, "discount_not_supported",
                    "Descontos ainda não estão disponíveis.", found);
        }
    }

    /**
     * @param ownIds ids das coleções que a campanha já tem
     * @return as coleções normalizadas, na ordem de exibição e numeradas de 0 em diante (só vale se não houver erros)
     */
    public List<CollectionInput> collections(List<CollectionInput> input, Set<UUID> ownIds, FieldChecks checks) {
        if (input == null) {
            return List.of();
        }
        if (input.size() > MAX_COLLECTIONS) {
            checks.reject("collections", "Use no máximo " + MAX_COLLECTIONS + " coleções.");
            return List.of();
        }
        Set<UUID> existingProducts = existingProducts(input);
        Set<UUID> seenIds = new HashSet<>();
        Set<String> seenSlugs = new HashSet<>();

        List<CollectionInput> checked = new ArrayList<>();
        for (int i = 0; i < input.size(); i++) {
            String path = "collections[" + i + "]";
            CollectionInput item = input.get(i);
            if (item == null) {
                checks.reject(path, "Item vazio.");
                continue;
            }
            if (item.id() != null) {
                if (!ownIds.contains(item.id())) {
                    checks.reject(path + ".id", "Coleção não encontrada nesta campanha.");
                } else if (!seenIds.add(item.id())) {
                    checks.reject(path + ".id", "Esta coleção está repetida.");
                }
            }
            checked.add(new CollectionInput(
                    item.id(),
                    checks.requiredText(path + ".title", item.title(), MAX_TITLE),
                    slug(path + ".slug", item.slug(), seenSlugs, checks),
                    position(path + ".position", item.position(), i, checks),
                    products(path + ".products", item.products(), existingProducts, checks)));
        }
        return inOrder(checked, CollectionInput::position,
                (c, position) -> new CollectionInput(c.id(), c.title(), c.slug(), position, c.products()));
    }

    private String slug(String field, String value, Set<String> seen, FieldChecks checks) {
        String slug = FieldChecks.blankToNull(value);
        if (slug == null) {
            checks.reject(field, "Preencha este campo.");
        } else if (slug.length() > MAX_SLUG) {
            checks.reject(field, "Use no máximo " + MAX_SLUG + " caracteres.");
        } else if (!SLUG.matcher(slug).matches()) {
            checks.reject(field, "Use letras minúsculas sem acento, números e hífens (ex.: ofertas-natal).");
        } else if (!seen.add(slug)) {
            checks.reject(field, "Já existe outra coleção com este identificador.");
        }
        return slug;
    }

    private List<CollectionInput.Product> products(
            String path, List<CollectionInput.Product> input, Set<UUID> existing, FieldChecks checks
    ) {
        if (input == null) {
            return List.of();
        }
        if (input.size() > MAX_PRODUCTS) {
            checks.reject(path, "Use no máximo " + MAX_PRODUCTS + " produtos por coleção.");
            return List.of();
        }
        Set<UUID> seen = new HashSet<>();
        List<CollectionInput.Product> checked = new ArrayList<>();
        for (int i = 0; i < input.size(); i++) {
            String itemPath = path + "[" + i + "]";
            CollectionInput.Product item = input.get(i);
            if (item == null) {
                checks.reject(itemPath, "Item vazio.");
                continue;
            }
            UUID id = item.productId();
            if (id == null) {
                checks.reject(itemPath + ".productId", "Escolha o produto.");
            } else if (!existing.contains(id)) {
                checks.reject(itemPath + ".productId", "Produto não encontrado.");
            } else if (!seen.add(id)) {
                checks.reject(itemPath + ".productId", "Este produto já está na coleção.");
            }
            checked.add(new CollectionInput.Product(
                    id, position(itemPath + ".position", item.position(), i, checks), null));
        }
        return inOrder(checked, CollectionInput.Product::position,
                (p, position) -> new CollectionInput.Product(p.productId(), position, null));
    }

    // uma consulta para a campanha inteira; produto desativado existe e é aceito
    private Set<UUID> existingProducts(List<CollectionInput> input) {
        List<UUID> ids = input.stream()
                .filter(c -> c != null && c.products() != null && c.products().size() <= MAX_PRODUCTS)
                .flatMap(c -> c.products().stream())
                .filter(Objects::nonNull)
                .map(CollectionInput.Product::productId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        return ids.isEmpty() ? Set.of() : products.findExistingIds(ids);
    }

    private static int position(String field, Integer value, int index, FieldChecks checks) {
        if (value == null) {
            return index;
        }
        if (value < 0) {
            checks.reject(field, "Use um número a partir de 0.");
        }
        return value;
    }

    /** Ordena pela posição enviada (empate mantém a ordem da lista) e renumera em sequência. */
    private static <T> List<T> inOrder(
            List<T> items, java.util.function.ToIntFunction<T> position,
            java.util.function.BiFunction<T, Integer, T> renumber
    ) {
        List<T> sorted = items.stream().sorted(Comparator.comparingInt(position)).toList();
        return IntStream.range(0, sorted.size()).mapToObj(i -> renumber.apply(sorted.get(i), i)).toList();
    }
}
