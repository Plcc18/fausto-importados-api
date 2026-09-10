package com.example.fausto_importados_api.services;

import com.cloudinary.Cloudinary;
import com.example.fausto_importados_api.dto.auth.ProductUpdateDTO;
import com.example.fausto_importados_api.mapper.ProductMapper;
import com.example.fausto_importados_api.model.Product;
import com.example.fausto_importados_api.model.enums.Category;
import com.example.fausto_importados_api.model.enums.OlfactiveFamily;
import com.example.fausto_importados_api.repository.ProductRepository;
import com.example.fausto_importados_api.services.exception.DuplicateProductException;
import com.example.fausto_importados_api.services.exception.InsufficientStockException;
import com.example.fausto_importados_api.services.exception.InvalidProductException;
import com.example.fausto_importados_api.services.exception.ProductNotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ProductService {

    private final ProductRepository productRepository;
    private final Cloudinary cloudinary;
    private final ProductMapper productMapper;

    public ProductService(ProductRepository productRepository, Cloudinary cloudinary, ProductMapper productMapper) {
        this.productRepository = productRepository;
        this.cloudinary = cloudinary;
        this.productMapper = productMapper;
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
                .orElseThrow(() -> new ProductNotFoundException("Product not found"));
    }

    public Page<Product> findFeatured(Pageable pageable) {
        return productRepository.findByFeaturedTrueAndActiveTrue(pageable);
    }

    public Page<Product> findByCategory(Category category, Pageable pageable) {
        return productRepository.findByCategoryAndActiveTrue(category, pageable);
    }

    public Page<Product> findByOlfactiveFamily(String olfactiveFamily, Pageable pageable) {
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
            throw new InsufficientStockException("Estoque insuficiente para o produto: " + product.getName());
        }

        product.setStockQuantity(newQty);

        if (newQty == 0) {
            product.setInStock(false);
        }

        return productRepository.save(product);
    }

    // Busca vários produtos ativos numa única query (evita N+1 ao validar itens de um pedido)
    public List<Product> findAllActiveByIds(Collection<UUID> ids) {
        return productRepository.findAllByIdInAndActiveTrue(ids);
    }

    // Decrementa o estoque de vários produtos de uma vez, numa única leitura e numa única escrita
    @Transactional
    public List<Product> decreaseStockBatch(Map<UUID, Integer> quantitiesByProductId) {
        List<Product> products = findAllActiveByIds(quantitiesByProductId.keySet());

        products.forEach(product -> {
            int quantity = quantitiesByProductId.get(product.getId());
            int newQty = product.getStockQuantity() - quantity;

            if (newQty < 0) {
                throw new InsufficientStockException("Estoque insuficiente para o produto: " + product.getName());
            }

            product.setStockQuantity(newQty);
            product.setInStock(newQty > 0);
        });

        return productRepository.saveAll(products);
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
            throw new InvalidProductException("Product name is required");
        }
    }

    private void validatePrice(Product product) {
        if (product.getPrice() == null) {
            throw new InvalidProductException("Price cannot be null");
        }
        if (product.getPrice().compareTo(BigDecimal.ZERO) <= 0) {
            throw new InvalidProductException("Price must be greater than zero");
        }
    }

    private void validateCategory(Product product) {
        if (product.getCategory() == null) {
            throw new InvalidProductException("Product category is required");
        }
    }

    private void validateDuplicate(Product product) {
        if (productRepository.existsByName(product.getName())) {
            throw new DuplicateProductException("Product already exists");
        }
    }

    private void validateId(Product product) {
        if (product.getId() != null) {
            throw new InvalidProductException("Product id must not be informed on creation");
        }
    }

    public void delete(UUID id) {
        Product product = findActiveById(id);
        productRepository.deleteById(id);
        productRepository.save(product);
    }

    public Product updatePartial(UUID id, ProductUpdateDTO dto) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ProductNotFoundException("Product not found"));

        productMapper.applyPatch(product, dto);
        syncInStock(product);

        return productRepository.save(product);
    }
}