package com.example.fausto_importados_api.services;

import com.example.fausto_importados_api.model.Product;
import com.example.fausto_importados_api.model.enums.Category;
import com.example.fausto_importados_api.model.enums.OlfactiveFamily;
import com.example.fausto_importados_api.repository.ProductRepository;
import com.example.fausto_importados_api.services.exception.BusinessException;
import com.example.fausto_importados_api.services.exception.ResourceNotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.UUID;

@Service
public class ProductService {

    private final ProductRepository productRepository;

    public ProductService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    public Page<Product> findAllActive(Pageable pageable) {
        return productRepository.findAllByActiveTrue(pageable);
    }

    public Product findActiveById(UUID id) {
        return productRepository.findByIdAndActiveTrue(id)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Product not found")
                );
    }

    public Page<Product> findFeatured(Pageable pageable) {
        return productRepository.findByFeaturedTrueAndActiveTrue(pageable);
    }

    public Page<Product> findByCategory(
            Category category,
            Pageable pageable
    ) {
        return productRepository.findByCategoryAndActiveTrue(category, pageable);
    }

    public Page<Product> findByOlfactiveFamily(
            OlfactiveFamily olfactiveFamily,
            Pageable pageable
    ) {
        return productRepository.findByOlfactiveFamilyAndActiveTrue(olfactiveFamily, pageable);
    }

    public Product save(Product product) {
        validateProduct(product);
        product.setActive(true);
        return productRepository.save(product);
    }
    private void validateProduct(Product product) {
        validateName(product);
        validatePrice(product);
        validateCategory(product);
        validateDuplicate(product);
        validateId(product);

    }

    private void validateName(Product product) {
        if (product.getName() == null) {
            throw new BusinessException("Product name is required");
        }
    }

    private void validatePrice(Product product) {
        if (product.getPrice() == null) {
            throw new BusinessException("Price cannot be null");
        }

        if (product.getPrice().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("Price must be greater than zero");
        }
    }

    private void validateCategory(Product product) {
        if (product.getCategory() == null) {
            throw new BusinessException("Product category is required");
        }
    }

    private void validateDuplicate(Product product) {
        if (productRepository.existsByName(product.getName())) {
            throw new BusinessException("Product alredy exists");
        }
    }

    private void validateId(Product product) {
        if (product.getId() != null) {
            throw new BusinessException("Product id must not be informed on cration");
        }
    }
}
