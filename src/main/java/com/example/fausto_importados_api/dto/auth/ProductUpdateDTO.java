package com.example.fausto_importados_api.dto.auth;

import com.example.fausto_importados_api.model.enums.Category;
import com.example.fausto_importados_api.model.enums.OlfactiveFamily;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Setter
@Getter
public class ProductUpdateDTO {

    private String name;
    private String brand;
    private String description;
    private OlfactiveFamily olfactiveFamily;
    private Category category;
    private String size;
    private BigDecimal price;
    private BigDecimal originalPrice;
    private String image;
    private Boolean featured;
    private Boolean inStock;
    private Boolean active;

    public ProductUpdateDTO() {}

}