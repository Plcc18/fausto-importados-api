package com.example.fausto_importados_api.controller;

import com.example.fausto_importados_api.dto.auth.ProductRequestDTO;
import com.example.fausto_importados_api.dto.auth.ProductResponseDTO;
import com.example.fausto_importados_api.dto.auth.ProductUpdateDTO;
import com.example.fausto_importados_api.mapper.ProductMapper;
import com.example.fausto_importados_api.model.Product;
import com.example.fausto_importados_api.model.enums.Category;
import com.example.fausto_importados_api.services.ProductService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/product")
public class ProductController {

    @Autowired
    private ProductService productService;

    @Autowired
    private ProductMapper productMapper;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // DTO para decrementar estoque de múltiplos produtos de uma vez
    public record StockDecreaseItem(UUID productId, int quantity) {}

    // DTO de resposta padrão
    public record ApiResponse(String timeStamp, String message) {}

    // ======================
    // GETs públicos
    // ======================
    @GetMapping
    public ResponseEntity<Page<ProductResponseDTO>> getAllActiveProducts(@PageableDefault(size = 2000) Pageable pageable) {
        Page<ProductResponseDTO> products = productService.findAllActive(pageable).map(productMapper::toResponseDTO);
        return ResponseEntity.ok(products);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProductResponseDTO> getProduct(@PathVariable UUID id) {
        Product p = productService.findActiveById(id);
        return ResponseEntity.ok(productMapper.toResponseDTO(p));
    }

    @GetMapping("/featured")
    public ResponseEntity<Page<ProductResponseDTO>> getFeaturedProducts(@PageableDefault(size = 2000) Pageable pageable) {
        Page<ProductResponseDTO> products = productService.findFeatured(pageable).map(productMapper::toResponseDTO);
        return ResponseEntity.ok(products);
    }

    @GetMapping("/category/{category}")
    public ResponseEntity<Page<ProductResponseDTO>> getProductsByCategory(
            @PathVariable Category category,
            @PageableDefault(size = 2000) Pageable pageable
    ) {
        Page<ProductResponseDTO> products = productService.findByCategory(category, pageable).map(productMapper::toResponseDTO);
        return ResponseEntity.ok(products);
    }

    @GetMapping("/olfactive-family/{olfactiveFamily}")
    public ResponseEntity<Page<ProductResponseDTO>> getProductsByOlfactiveFamily(
            @PathVariable String olfactiveFamily,
            @PageableDefault(size = 2000) Pageable pageable
    ) {
        Page<ProductResponseDTO> products = productService.findByOlfactiveFamily(olfactiveFamily, pageable).map(productMapper::toResponseDTO);
        return ResponseEntity.ok(products);
    }

    // ======================
    // POST - Criar produto
    // ======================
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse> postProduct(
            @Valid @RequestBody ProductRequestDTO dto
    ) {
        productService.save(productMapper.toEntity(dto));
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
        items.forEach(item -> productService.decreaseStock(item.productId(), item.quantity()));
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
            ProductRequestDTO dto = objectMapper.readValue(productJson, ProductRequestDTO.class);

            productMapper.updateEntityFromRequest(existing, dto);

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
    public ResponseEntity<ProductResponseDTO> updatePartial(
            @PathVariable UUID id,
            @RequestBody ProductUpdateDTO dto
    ) {
        Product updated = productService.updatePartial(id, dto);
        return ResponseEntity.ok(productMapper.toResponseDTO(updated));
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
}