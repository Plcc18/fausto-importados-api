package com.example.fausto_importados_api.dto.auth;

import com.example.fausto_importados_api.dto.validation.ValidOlfactiveFamily;
import com.example.fausto_importados_api.model.enums.Category;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Setter
@Getter
public class ProductUpdateDTO {

    private String name;
    private String brand;
    private String description;

    @ValidOlfactiveFamily
    private String olfactiveFamily;
    private Category category;
    private String size;
    @Positive(message = "Price must be greater than zero")
    private BigDecimal price;
    @Positive(message = "Original price must be greater than zero")
    private BigDecimal originalPrice;
    private String image;
    private Boolean featured;
    private Boolean inStock;
    private Boolean active;
    @Min(value = 0, message = "Stock quantity cannot be negative")
    private Integer stockQuantity;

    /** Versão lida pelo painel antes de editar. */
    private Long version;

    public ProductUpdateDTO() {}
}