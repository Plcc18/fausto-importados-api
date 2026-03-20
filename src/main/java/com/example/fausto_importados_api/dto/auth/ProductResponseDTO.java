package com.example.fausto_importados_api.dto.auth;

import com.example.fausto_importados_api.model.Product;
import com.example.fausto_importados_api.model.enums.Category;
import com.example.fausto_importados_api.model.enums.OlfactiveFamily;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record ProductResponseDTO(
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
        Boolean active,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static ProductResponseDTO fromProduct(Product product) {
        return new ProductResponseDTO(
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
                product.getActive(),
                product.getCreatedAt(),
                product.getUpdatedAt()
        );
    }
}