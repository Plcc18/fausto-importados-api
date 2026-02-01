package com.example.fausto_importados_api.service;

import com.example.fausto_importados_api.model.Product;
import com.example.fausto_importados_api.model.enums.Category;
import com.example.fausto_importados_api.repository.ProductRepository;
import com.example.fausto_importados_api.services.ProductService;
import com.example.fausto_importados_api.services.exception.BusinessException;
import com.example.fausto_importados_api.services.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;


import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    @Mock
    private ProductRepository productRepository;

    @InjectMocks
    private ProductService productService;

    private Product product;

    //executar primeiro
    @BeforeEach
    void setUp() {
        product = new Product();
        product.setName("Ceboliinha");
        product.setPrice(new BigDecimal(100));
        product.setCategory(Category.MASCULINO);
    }

    //Find methods
    @Test
    void shouldReturnAllActiveProducts() {
        Pageable pageable = PageRequest.of(0,10);
        Page<Product> page = new PageImpl<>(List.of(product));

        when(productRepository.findAllByActiveTrue(pageable))
                .thenReturn(page);

        Page<Product> result = productService.findAllActive(pageable);

        assertThat(result.getContent()).hasSize(1);
        verify(productRepository).findAllByActiveTrue(pageable);
    }

    @Test
    void shouldFindActiveProductById() {
        UUID id = UUID.randomUUID();
        product.setId(id);

        when(productRepository.findByIdAndActiveTrue(id))
                .thenReturn(Optional.of(product));

        Product result = productService.findActiveById(id);

        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(id);
        verify(productRepository).findByIdAndActiveTrue(id);
    }

    @Test
    void shouldThrowExceptionsWhenProductNotFound() {
        UUID id = UUID.randomUUID();

        when(productRepository.findByIdAndActiveTrue(id))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> productService.findActiveById(id))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Product not found");
    }

    //Save
    @Test
    void shouldSaveProductSuccesfully() {
        when(productRepository.existsByName(product.getName()))
                .thenReturn(false);

        when(productRepository.save(any(Product.class)))
                .thenAnswer(invocationOnMock -> invocationOnMock.getArgument(0));

        Product saved = productService.save(product);

        assertThat(saved.getActive()).isTrue();
        verify(productRepository).save(product);
    }

    @Test
    void shouldThrowExceptionWhenProductNameIsNull() {
        product.setName(null);

        assertThatThrownBy(() -> productService.save(product))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Product name is required");

        verify(productRepository, never()).save(any());
    }

    @Test
    void shouldThrowExceptionWhenPriceIsNull() {
        product.setPrice(null);

        assertThatThrownBy(() -> productService.save(product))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Price cannot be null");
    }

    @Test
    void shouldThrowExceptionWhenPriceIsZeroOrNegative() {
        product.setPrice(BigDecimal.ZERO);

        assertThatThrownBy(() -> productService.save(product))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Price must be greater than zero");
    }

    @Test
    void shouldThrowExceptionWhenCategoryIsNull() {
        product.setCategory(null);

        assertThatThrownBy(() -> productService.save(product))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Product category is required");
    }

    @Test
    void shouldThrowExceptionWhenProductAlreadyExists() {
        when(productRepository.existsByName(product.getName()))
                .thenReturn(true);

        assertThatThrownBy(() -> productService.save(product))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Product alredy exists");
    }

    @Test
    void shouldThrowExceptionWhenIdIsProvidedOnCreation() {
        product.setId(UUID.randomUUID());

        assertThatThrownBy(() -> productService.save(product))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Product id must not be informed on cration");
    }
}
