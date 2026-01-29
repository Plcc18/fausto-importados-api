package com.example.fausto_importados_api.model;

import com.example.fausto_importados_api.model.enums.Category;
import com.example.fausto_importados_api.model.enums.OlfactiveFamily;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "products")
@Data
public class Product {

    @Id
    @GeneratedValue
    private UUID id;

    @NotBlank(message = "Name is mandatory!")
    @Column(nullable = false, length = 150)
    private String name;

    @NotBlank(message = "Brand is mandatory!")
    @Column(nullable = false, length = 100)
    private String brand;

    @NotBlank(message = "Description is mandatory!")
    @Column(nullable = false, columnDefinition = "TEXT")
    private String description;

    @NotNull(message = "OlfactiveFamily is mandatory!")
    @Enumerated(EnumType.STRING)
    @Column(name = "olfactive_family", nullable = false)
    private OlfactiveFamily olfactiveFamily;

    @NotNull(message = "Category is mandatory!")
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Category category;

    @NotBlank(message = "Size is mandatory!")
    @Column(nullable = false, length = 50)
    private String size;

    @NotNull(message = "Price is mandatory!")
    @Positive
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @Positive
    @Column(name = "original_price", precision = 10, scale = 2)
    private BigDecimal originalPrice;

    @NotBlank(message = "Image is mandatory")
    @Column(nullable = false, columnDefinition = "TEXT")
    private String image;

    @NotNull
    @Column(nullable = false)
    private Boolean featured = false;

    @NotNull(message = "In stock is mandatory")
    @Column(name = "in_stock", nullable = false)
    private Boolean inStock;

    @NotNull
    @Column(nullable = false)
    private Boolean active = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}

