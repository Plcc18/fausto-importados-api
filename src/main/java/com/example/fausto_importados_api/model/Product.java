package com.example.fausto_importados_api.model;

import com.example.fausto_importados_api.model.enums.Category;
import com.example.fausto_importados_api.model.enums.OlfactiveFamily;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
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
    @Column(nullable = false)
    private String description;

    @NotBlank(message = "OlfactiveFamily is mandatory!")
    @Column(name = "olfactive_family", nullable = false)
    @Enumerated(EnumType.STRING)
    private OlfactiveFamily olfactivefamily;

    @NotBlank(message = "Category is mandatory!")
    @Column(name = "category")
    @Enumerated(EnumType.STRING)
    private Category category;

    @NotBlank(message = "Size is mandatory!")
    @Column(nullable = false, length = 50)
    private String size;

    @NotBlank(message = "Price is mandatory!")
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @Column(name = "original_price", precision = 10, scale = 2)
    private BigDecimal originalPrice;

    @NotBlank(message = "Image is mandatory")
    @Column(nullable = false, columnDefinition = "TEXT")
    private String image;

    @Column(columnDefinition = "BOOLEAN DEFAULT FALSE")
    private Boolean featured = false;

    @NotNull(message = "In stock is mandatory")
    @Column(name = "in_stock", nullable = false)
    private Boolean inStock;
}
