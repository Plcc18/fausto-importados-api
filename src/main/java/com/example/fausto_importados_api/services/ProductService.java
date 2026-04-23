package com.example.fausto_importados_api.services;

import com.cloudinary.Cloudinary;
import com.example.fausto_importados_api.dto.auth.ProductUpdateDTO;
import com.example.fausto_importados_api.model.Product;
import com.example.fausto_importados_api.model.enums.Category;
import com.example.fausto_importados_api.model.enums.OlfactiveFamily;
import com.example.fausto_importados_api.repository.ProductRepository;
import com.example.fausto_importados_api.services.exception.BusinessException;
import com.example.fausto_importados_api.services.exception.ResourceNotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

@Service
public class ProductService {

    private final ProductRepository productRepository;
    private final Cloudinary cloudinary;

    public ProductService(ProductRepository productRepository, Cloudinary cloudinary) {
        this.productRepository = productRepository;
        this.cloudinary = cloudinary;
    }

    public String uploadImage(MultipartFile file) {
        try {
            Map<?, ?> uploadResult = cloudinary.uploader().upload(
                    file.getBytes(),
                    Map.of()
            );
            return uploadResult.get("secure_url").toString();
        } catch (IOException e) {
            throw new RuntimeException("Erro ao fazer upload da imagem", e);
        }
    }

    public Page<Product> findAllActive(Pageable pageable) {
        return productRepository.findAllByActiveTrue(pageable);
    }

    public Product findActiveById(UUID id) {
        return productRepository.findByIdAndActiveTrue(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found"));
    }

    public Page<Product> findFeatured(Pageable pageable) {
        return productRepository.findByFeaturedTrueAndActiveTrue(pageable);
    }

    public Page<Product> findByCategory(Category category, Pageable pageable) {
        return productRepository.findByCategoryAndActiveTrue(category, pageable);
    }

    public Page<Product> findByOlfactiveFamily(OlfactiveFamily olfactiveFamily, Pageable pageable) {
        return productRepository.findByOlfactiveFamilyAndActiveTrue(olfactiveFamily, pageable);
    }

    public Product save(Product product) {
        validateProduct(product);
        product.setActive(true);

        // Garante consistência: se stockQuantity > 0, inStock = true
        syncInStock(product);

        return productRepository.save(product);
    }

    public Product update(Product product) {
        validateName(product);
        validatePrice(product);
        validateCategory(product);

        syncInStock(product);

        return productRepository.save(product);
    }

    // Decrementa o estoque em `quantity` unidades ao finalizar um pedido.
    // Quando o estoque chega a 0, marca inStock = false automaticamente.
    @Transactional
    public Product decreaseStock(UUID id, int quantity) {
        Product product = findActiveById(id);

        int newQty = product.getStockQuantity() - quantity;

        if (newQty < 0) {
            throw new BusinessException("Estoque insuficiente para o produto: " + product.getName());
        }

        product.setStockQuantity(newQty);

        if (newQty == 0) {
            product.setInStock(false);
        }

        return productRepository.save(product);
    }

    // Mantém inStock sincronizado com stockQuantity
    private void syncInStock(Product product) {
        if (product.getStockQuantity() != null) {
            if (product.getStockQuantity() > 0) {
                product.setInStock(true);
            } else {
                product.setInStock(false);
            }
        }
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
            throw new BusinessException("Product already exists");
        }
    }

    private void validateId(Product product) {
        if (product.getId() != null) {
            throw new BusinessException("Product id must not be informed on creation");
        }
    }

    public void delete(UUID id) {
        Product product = findActiveById(id);
        productRepository.deleteById(id);
        productRepository.save(product);
    }

    public Product updatePartial(UUID id, ProductUpdateDTO dto) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Produto não encontrado"));

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

        syncInStock(product);

        return productRepository.save(product);
    }
}