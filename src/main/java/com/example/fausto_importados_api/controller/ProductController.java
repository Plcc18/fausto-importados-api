package com.example.fausto_importados_api.controller;

import com.example.fausto_importados_api.dto.auth.ProductUpdateDTO;
import com.example.fausto_importados_api.model.Product;
import com.example.fausto_importados_api.model.enums.Category;
import com.example.fausto_importados_api.model.enums.OlfactiveFamily;
import com.example.fausto_importados_api.services.ProductService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/product")
public class ProductController {

    @Autowired
    private ProductService productService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // DTO de resposta do produto
    public record ProductDTO(
            UUID id,
            String name,
            String brand,
            String description,
            OlfactiveFamily olfactiveFamily,
            Category category,
            String size,
            BigDecimal price,
            BigDecimal originalPrice,
            String image,
            Boolean featured,
            Boolean inStock,
            Integer stockQuantity,
            Boolean active
    ) {}

    // DTO para decrementar estoque de múltiplos produtos de uma vez
    public record StockDecreaseItem(UUID productId, int quantity) {}

    // DTO de resposta padrão
    public record ApiResponse(String timeStamp, String message) {}

    // ======================
    // GETs públicos
    // ======================
    @GetMapping
    public ResponseEntity<Page<Product>> getAllActiveProducts(Pageable pageable) {
        Page<Product> products = productService.findAllActive(pageable);
        return ResponseEntity.ok(products);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProductDTO> getProduct(@PathVariable UUID id) {
        Product p = productService.findActiveById(id);
        return ResponseEntity.ok(mapToDTO(p));
    }

    @GetMapping("/featured")
    public ResponseEntity<Page<Product>> getFeaturedProducts(Pageable pageable) {
        Page<Product> products = productService.findFeatured(pageable);
        return ResponseEntity.ok(products);
    }

    @GetMapping("/category/{category}")
    public ResponseEntity<Page<Product>> getProductsByCategory(
            @PathVariable Category category,
            Pageable pageable
    ) {
        Page<Product> products = productService.findByCategory(category, pageable);
        return ResponseEntity.ok(products);
    }

    @GetMapping("/olfactive-family/{olfactiveFamily}")
    public ResponseEntity<Page<Product>> getProductsByOlfactiveFamily(
            @PathVariable OlfactiveFamily olfactiveFamily,
            Pageable pageable
    ) {
        Page<Product> products = productService.findByOlfactiveFamily(olfactiveFamily, pageable);
        return ResponseEntity.ok(products);
    }

    // ======================
    // POST - Criar produto
    // ======================
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse> postProduct(
            @Valid @RequestBody Product p
    ) {
        productService.save(p);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ApiResponse(Instant.now().toString(), "Product created successfully"));
    }

    // ======================
    // POST - Decrementar estoque ao finalizar pedido (público — chamado pelo frontend)
    // Recebe uma lista de itens { productId, quantity } e decrementa cada um.
    // ======================
    @PostMapping("/decrease-stock")
    public ResponseEntity<ApiResponse> decreaseStock(
            @RequestBody List<StockDecreaseItem> items
    ) {
        for (StockDecreaseItem item : items) {
            productService.decreaseStock(item.productId(), item.quantity());
        }
        return ResponseEntity.ok(
                new ApiResponse(Instant.now().toString(), "Stock updated successfully")
        );
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
        try {
            Product existing = productService.findActiveById(id);
            Product p = objectMapper.readValue(productJson, Product.class);

            existing.setName(p.getName());
            existing.setBrand(p.getBrand());
            existing.setDescription(p.getDescription());
            existing.setOlfactiveFamily(p.getOlfactiveFamily());
            existing.setCategory(p.getCategory());
            existing.setSize(p.getSize());
            existing.setPrice(p.getPrice());
            existing.setOriginalPrice(p.getOriginalPrice());
            existing.setFeatured(p.getFeatured());
            existing.setInStock(p.getInStock());
            existing.setActive(p.getActive());
            existing.setStockQuantity(p.getStockQuantity());

            if (file != null && !file.isEmpty()) {
                String imageUrl = productService.uploadImage(file);
                existing.setImage(imageUrl);
            }

            productService.update(existing);

            return ResponseEntity.ok(
                    new ApiResponse(Instant.now().toString(), "Product updated successfully")
            );
        } catch (Exception e) {
            throw new RuntimeException("Erro ao atualizar produto", e);
        }
    }

    // ======================
    // PATCH - Atualização parcial
    // ======================
    @PatchMapping("/{id}")
    public ResponseEntity<Product> updatePartial(
            @PathVariable UUID id,
            @RequestBody ProductUpdateDTO dto
    ) {
        Product updated = productService.updatePartial(id, dto);
        return ResponseEntity.ok(updated);
    }

    // ======================
    // DELETE - Deletar produto
    // ======================
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse> deleteProduct(@PathVariable UUID id) {
        productService.delete(id);
        return ResponseEntity.ok(
                new ApiResponse(Instant.now().toString(), "Product deleted successfully")
        );
    }

    // ======================
    // Mapper
    // ======================
    private ProductDTO mapToDTO(Product product) {
        return new ProductDTO(
                product.getId(),
                product.getName(),
                product.getBrand(),
                product.getDescription(),
                product.getOlfactiveFamily(),
                product.getCategory(),
                product.getSize(),
                product.getPrice(),
                product.getOriginalPrice(),
                product.getImage(),
                product.getFeatured(),
                product.getInStock(),
                product.getStockQuantity(),
                product.getActive()
        );
    }
}