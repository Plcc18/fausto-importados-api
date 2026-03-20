package com.example.fausto_importados_api.dto.auth;

import com.example.fausto_importados_api.model.enums.Category;
import com.example.fausto_importados_api.model.enums.OlfactiveFamily;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record ProductRequestDTO(
        @NotBlank(message = "Name is mandatory!")
        String name,

        @NotBlank(message = "Brand is mandatory!")
        String brand,

        @NotBlank(message = "Description is mandatory!")
        String description,

        @NotNull(message = "OlfactiveFamily is mandatory!")
        OlfactiveFamily olfactiveFamily,

        @NotNull(message = "Category is mandatory!")
        Category category,

        @NotBlank(message = "Size is mandatory!")
        String size,

        @NotNull(message = "Price is mandatory!")
        @Positive(message = "Price must be greater than zero")
        BigDecimal price,

        @Positive(message = "Original price must be greater than zero")
        BigDecimal originalPrice,

        @NotBlank(message = "Image is mandatory!")
        String image,

        Boolean featured,

        @NotNull(message = "In stock is mandatory!")
        Boolean inStock
) {}