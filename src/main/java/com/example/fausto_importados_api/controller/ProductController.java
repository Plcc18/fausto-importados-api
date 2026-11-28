package com.example.fausto_importados_api.controller;

import com.example.fausto_importados_api.audit.AuditAction;
import com.example.fausto_importados_api.audit.AuditDiff;
import com.example.fausto_importados_api.audit.AuditService;
import com.example.fausto_importados_api.concurrency.IdempotencyService;
import com.example.fausto_importados_api.concurrency.Transactions;
import com.example.fausto_importados_api.dto.auth.ProductRequestDTO;
import com.example.fausto_importados_api.dto.auth.ProductResponseDTO;
import com.example.fausto_importados_api.dto.auth.ProductUpdateDTO;
import com.example.fausto_importados_api.mapper.ProductMapper;
import com.example.fausto_importados_api.model.Product;
import com.example.fausto_importados_api.model.User;
import com.example.fausto_importados_api.model.enums.Category;
import com.example.fausto_importados_api.services.ProductService;
import com.example.fausto_importados_api.services.exception.InvalidProductException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/product")
public class ProductController {

    @Autowired
    private ProductService productService;

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private AuditService audit;

    @Autowired
    private Validator validator;

    @Autowired
    private IdempotencyService idempotency;

    @Autowired
    private Transactions transactions;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // DTO para decrementar estoque de múltiplos produtos de uma vez
    public record StockDecreaseItem(UUID productId, int quantity) {}

    // DTO de resposta padrão
    public record ApiResponse(String timeStamp, String message) {}

    // ======================
    // GETs públicos
    // ======================
    @GetMapping
    public ResponseEntity<Page<ProductResponseDTO>> getAllActiveProducts(
            @PageableDefault(size = 2000) Pageable pageable, @AuthenticationPrincipal User user
    ) {
        return ResponseEntity.ok(toPage(productService.findAllActive(pageable), user));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProductResponseDTO> getProduct(@PathVariable UUID id, @AuthenticationPrincipal User user) {
        Product p = productService.findActiveById(id);
        return ResponseEntity.ok(productMapper.toResponseDTO(p, isAdmin(user)));
    }

    @GetMapping("/featured")
    public ResponseEntity<Page<ProductResponseDTO>> getFeaturedProducts(
            @PageableDefault(size = 2000) Pageable pageable, @AuthenticationPrincipal User user
    ) {
        return ResponseEntity.ok(toPage(productService.findFeatured(pageable), user));
    }

    @GetMapping("/category/{category}")
    public ResponseEntity<Page<ProductResponseDTO>> getProductsByCategory(
            @PathVariable Category category,
            @PageableDefault(size = 2000) Pageable pageable,
            @AuthenticationPrincipal User user
    ) {
        return ResponseEntity.ok(toPage(productService.findByCategory(category, pageable), user));
    }

    @GetMapping("/olfactive-family/{olfactiveFamily}")
    public ResponseEntity<Page<ProductResponseDTO>> getProductsByOlfactiveFamily(
            @PathVariable String olfactiveFamily,
            @PageableDefault(size = 2000) Pageable pageable,
            @AuthenticationPrincipal User user
    ) {
        return ResponseEntity.ok(toPage(productService.findByOlfactiveFamily(olfactiveFamily, pageable), user));
    }

    // ======================
    // POST - Criar produto
    // ======================
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> postProduct(
            @RequestHeader(value = IdempotencyService.HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody ProductRequestDTO dto
    ) {
        return idempotency.execute(idempotencyKey, "products.create", dto, () -> {
            Product saved = productService.save(productMapper.toEntity(dto));
            transactions.afterCommit(() -> audit.recordSuccess(
                    AuditAction.PRODUCT_CREATED, "PRODUCT", saved.getId(), AuditDiff.created(saved)));
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(new ApiResponse(Instant.now().toString(), "Product created successfully"));
        });
    }

    // ======================
    // POST - Decrementar estoque (ADMIN)
    // Recebe uma lista de itens { productId, quantity } e decrementa todos ou nenhum.
    // ======================
    @PostMapping("/decrease-stock")
    public ResponseEntity<?> decreaseStock(
            @RequestHeader(value = IdempotencyService.HEADER, required = false) String idempotencyKey,
            @RequestBody List<StockDecreaseItem> items
    ) {
        SortedMap<UUID, Integer> quantities = quantitiesByProduct(items);
        return idempotency.execute(idempotencyKey, "products.decrease-stock", items, () -> {
            productService.decreaseStock(quantities);
            transactions.afterCommit(() -> audit.recordSuccess(
                    AuditAction.STOCK_DECREASED, "PRODUCT", null, Map.of("items", items.stream()
                            .map(item -> Map.of("productId", String.valueOf(item.productId()), "quantity", item.quantity()))
                            .toList())));
            return ResponseEntity.ok(
                    new ApiResponse(Instant.now().toString(), "Stock updated successfully")
            );
        });
    }

    // ======================
    // PUT - Atualizar produto
    // ======================
    @PutMapping(value = "/{id}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse> putProduct(
            @PathVariable UUID id,
            @RequestPart("product") String productJson,
            @RequestPart(value = "image", required = false) MultipartFile file
    ) {
        ProductRequestDTO dto = parse(productJson);

        // @RequestPart não passa pelo @Valid do Spring — valida manualmente
        Set<ConstraintViolation<ProductRequestDTO>> violations = validator.validate(dto);
        if (!violations.isEmpty()) {
            String message = violations.stream()
                    .map(ConstraintViolation::getMessage)
                    .collect(Collectors.joining("; "));
            throw new InvalidProductException(message);
        }

        // Antes do upload: uma imagem enviada para um pedido recusado ficaria órfã no Cloudinary
        ProductService.checkOriginalPrice(dto.price(), dto.originalPrice());

        // O upload é lento e externo: acontece antes (e fora) da transação que grava o produto
        String imageUrl = null;
        if (file != null && !file.isEmpty()) {
            productService.checkEditable(id, dto.version());
            imageUrl = productService.uploadImage(file);
        }

        ProductService.Change change = productService.replace(id, dto, imageUrl);
        audit.recordSuccess(AuditAction.PRODUCT_UPDATED, "PRODUCT", id,
                Map.of("changes", AuditDiff.diff(change.before(), AuditDiff.product(change.product()))));

        return ResponseEntity.ok(
                new ApiResponse(Instant.now().toString(), "Product updated successfully")
        );
    }

    // ======================
    // PATCH - Atualização parcial
    // ======================
    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ProductResponseDTO> updatePartial(
            @PathVariable UUID id,
            @Valid @RequestBody ProductUpdateDTO dto
    ) {
        Product updated = productService.updatePartial(id, dto).product();
        Map<String, Object> requested = new LinkedHashMap<>();
        objectMapper.convertValue(dto, new TypeReference<Map<String, Object>>() {})
                .forEach((field, value) -> {
                    if (value != null && !field.equals("version")) requested.put(field, value);
                });
        audit.recordSuccess(AuditAction.PRODUCT_UPDATED, "PRODUCT", id, Map.of("requested", requested));
        return ResponseEntity.ok(productMapper.toResponseDTO(updated, true));
    }

    // ======================
    // DELETE - Desativar produto (exclusão lógica)
    // ======================
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse> deleteProduct(@PathVariable UUID id) {
        Product product = productService.delete(id);
        audit.recordSuccess(AuditAction.PRODUCT_DELETED, "PRODUCT", id, AuditDiff.created(product));
        return ResponseEntity.ok(
                new ApiResponse(Instant.now().toString(), "Product deleted successfully")
        );
    }

    // ───────────────────────────── internos ─────────────────────────────

    private Page<ProductResponseDTO> toPage(Page<Product> products, User user) {
        boolean admin = isAdmin(user);
        return products.map(product -> productMapper.toResponseDTO(product, admin));
    }

    private static boolean isAdmin(User user) {
        return user != null && user.isAdmin();
    }

    private ProductRequestDTO parse(String productJson) {
        try {
            return objectMapper.readValue(productJson, ProductRequestDTO.class);
        } catch (JsonProcessingException e) {
            throw new InvalidProductException("Invalid product data");
        }
    }

    /** Soma itens repetidos; a ordem por id é a ordem em que os produtos são travados. */
    private static SortedMap<UUID, Integer> quantitiesByProduct(List<StockDecreaseItem> items) {
        if (items.isEmpty() || items.size() > 100 || items.stream().anyMatch(item -> item == null
                || item.productId() == null || item.quantity() <= 0 || item.quantity() > 100_000)) {
            throw new InvalidProductException("Each item needs a productId and a quantity greater than zero");
        }
        return items.stream().collect(Collectors.toMap(
                StockDecreaseItem::productId, StockDecreaseItem::quantity, Math::addExact, TreeMap::new));
    }
}
