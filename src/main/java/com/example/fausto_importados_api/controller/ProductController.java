package com.example.fausto_importados_api.controller;

import com.example.fausto_importados_api.dto.auth.ProductUpdateDTO;
import com.example.fausto_importados_api.model.Product;
import com.example.fausto_importados_api.model.enums.Category;
import com.example.fausto_importados_api.model.enums.OlfactiveFamily;
import com.example.fausto_importados_api.services.ProductService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/product")
public class ProductController {

    @Autowired
    private ProductService productService;

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
            Boolean active
    ) {}

    // DTO de resposta padrão
    public record ApiResponse(String timeStamp, String message) {}

    // GET todos os produtos ativos (público)
    @GetMapping
    public ResponseEntity<Page<Product>> getAllActiveProducts(Pageable pageable) {
        Page<Product> products = productService.findAllActive(pageable);
        return ResponseEntity.ok(products);
    }

    // GET produto por ID (público)
    @GetMapping("/{id}")
    public ResponseEntity<ProductDTO> getProduct(@PathVariable UUID id) {
        Product p = productService.findActiveById(id);
        return ResponseEntity.ok(mapToDTO(p));
    }

    // GET produtos em destaque (público)
    @GetMapping("/featured")
    public ResponseEntity<Page<Product>> getFeaturedProducts(Pageable pageable) {
        Page<Product> products = productService.findFeatured(pageable);
        return ResponseEntity.ok(products);
    }

    // GET produtos por categoria (público)
    @GetMapping("/category/{category}")
    public ResponseEntity<Page<Product>> getProductsByCategory(
            @PathVariable Category category,
            Pageable pageable
    ) {
        Page<Product> products = productService.findByCategory(category, pageable);
        return ResponseEntity.ok(products);
    }

    // GET produtos por família olfativa (público)
    @GetMapping("/olfactive-family/{olfactiveFamily}")
    public ResponseEntity<Page<Product>> getProductsByOlfactiveFamily(
            @PathVariable OlfactiveFamily olfactiveFamily,
            Pageable pageable
    ) {
        Page<Product> products = productService.findByOlfactiveFamily(olfactiveFamily, pageable);
        return ResponseEntity.ok(products);
    }

    // POST - Criar novo produto (apenas ADMIN)
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse> postProduct(@Valid @RequestBody Product p) {
        productService.save(p);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ApiResponse(
                        Instant.now().toString(),
                        "Product created successfully"
                ));
    }

    // PUT - Atualizar produto (apenas ADMIN)
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse> putProduct(
            @PathVariable UUID id,
            @Valid @RequestBody Product p
    ) {
        Product existing = productService.findActiveById(id);

        existing.setName(p.getName());
        existing.setBrand(p.getBrand());
        existing.setDescription(p.getDescription());
        existing.setOlfactiveFamily(p.getOlfactiveFamily());
        existing.setCategory(p.getCategory());
        existing.setSize(p.getSize());
        existing.setPrice(p.getPrice());
        existing.setOriginalPrice(p.getOriginalPrice());
        existing.setImage(p.getImage());
        existing.setFeatured(p.getFeatured());
        existing.setInStock(p.getInStock());
        existing.setActive(p.getActive());

        productService.save(existing);

        return ResponseEntity.ok(
                new ApiResponse(
                        Instant.now().toString(),
                        "Product updated successfully"
                )
        );
    }

    @PatchMapping("/{id}")
    public ResponseEntity<Product> updatePartial(
            @PathVariable UUID id,
            @RequestBody ProductUpdateDTO dto
    ) {
        Product updated = productService.updatePartial(id, dto);
        return ResponseEntity.ok(updated);
    }

    // DELETE - Deletar produto (apenas ADMIN)
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse> deleteProduct(@PathVariable UUID id) {
        productService.delete(id);

        return ResponseEntity.ok(
                new ApiResponse(
                        Instant.now().toString(),
                        "Product deleted successfully"
                )
        );
    }

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
                product.getActive()
        );
    }
}