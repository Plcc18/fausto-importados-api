package com.example.fausto_importados_api.mapper;

import com.example.fausto_importados_api.dto.auth.ProductRequestDTO;
import com.example.fausto_importados_api.dto.auth.ProductResponseDTO;
import com.example.fausto_importados_api.dto.auth.ProductUpdateDTO;
import com.example.fausto_importados_api.model.Product;
import org.springframework.stereotype.Component;

@Component
public class ProductMapper {

    public Product toEntity(ProductRequestDTO dto) {
        Product product = new Product();
        applyRequest(product, dto);
        return product;
    }

    public void updateEntityFromRequest(Product existing, ProductRequestDTO dto) {
        applyRequest(existing, dto);
    }

    private void applyRequest(Product product, ProductRequestDTO dto) {
        product.setName(dto.name());
        product.setBrand(dto.brand());
        product.setDescription(dto.description());
        product.setOlfactiveFamily(dto.olfactiveFamily());
        product.setCategory(dto.category());
        product.setSize(dto.size());
        product.setPrice(dto.price());
        product.setOriginalPrice(dto.originalPrice());
        product.setImage(dto.image());
        product.setFeatured(dto.featured() != null ? dto.featured() : false);
        product.setInStock(dto.inStock());
        product.setStockQuantity(dto.stockQuantity());
    }

    public void applyPatch(Product product, ProductUpdateDTO dto) {
        if (dto.getName() != null) product.setName(dto.getName());
        if (dto.getBrand() != null) product.setBrand(dto.getBrand());
        if (dto.getDescription() != null) product.setDescription(dto.getDescription());
        if (dto.getOlfactiveFamily() != null) product.setOlfactiveFamily(dto.getOlfactiveFamily());
        if (dto.getCategory() != null) product.setCategory(dto.getCategory());
        if (dto.getSize() != null) product.setSize(dto.getSize());
        if (dto.getPrice() != null) product.setPrice(dto.getPrice());
        if (dto.getOriginalPrice() != null) product.setOriginalPrice(dto.getOriginalPrice());
        if (dto.getImage() != null) product.setImage(dto.getImage());
        if (dto.getFeatured() != null) product.setFeatured(dto.getFeatured());
        if (dto.getInStock() != null) product.setInStock(dto.getInStock());
        if (dto.getActive() != null) product.setActive(dto.getActive());
        if (dto.getStockQuantity() != null) product.setStockQuantity(dto.getStockQuantity());
    }

    public ProductResponseDTO toResponseDTO(Product product) {
        return ProductResponseDTO.fromProduct(product);
    }
}
