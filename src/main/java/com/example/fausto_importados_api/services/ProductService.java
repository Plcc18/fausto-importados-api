package com.example.fausto_importados_api.services;

import com.cloudinary.Cloudinary;
import com.example.fausto_importados_api.audit.AuditDiff;
import com.example.fausto_importados_api.concurrency.Transactions;
import com.example.fausto_importados_api.concurrency.Versions;
import com.example.fausto_importados_api.dto.auth.ProductRequestDTO;
import com.example.fausto_importados_api.dto.auth.ProductUpdateDTO;
import com.example.fausto_importados_api.mapper.ProductMapper;
import com.example.fausto_importados_api.model.Product;
import com.example.fausto_importados_api.model.enums.Category;
import com.example.fausto_importados_api.repository.ProductRepository;
import com.example.fausto_importados_api.services.exception.DuplicateProductException;
import com.example.fausto_importados_api.services.exception.InsufficientStockException;
import com.example.fausto_importados_api.services.exception.InvalidProductException;
import com.example.fausto_importados_api.services.exception.ProductNotFoundException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.UUID;

@Service
public class ProductService {

    private static final String DUPLICATE = "Product already exists";
    private static final UUID NO_ID = new UUID(0, 0);

    /** Produto depois de uma edição e como estava antes (para a auditoria). */
    public record Change(Map<String, Object> before, Product product) {
    }

    private final ProductRepository productRepository;
    private final Cloudinary cloudinary;
    private final ProductMapper productMapper;
    private final Transactions transactions;
    private final Versions versions;

    public ProductService(
            ProductRepository productRepository,
            Cloudinary cloudinary,
            ProductMapper productMapper,
            Transactions transactions,
            Versions versions
    ) {
        this.productRepository = productRepository;
        this.cloudinary = cloudinary;
        this.productMapper = productMapper;
        this.transactions = transactions;
        this.versions = versions;
    }

    /** Chamada externa e lenta: nunca dentro de uma transação. */
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

    // Busca vários produtos ativos numa única query (evita N+1 ao montar um pedido)
    public List<Product> findAllActiveByIds(Collection<UUID> ids) {
        return productRepository.findAllByIdInAndActiveTrue(ids);
    }

    public Product save(Product product) {
        validateName(product);
        validatePrice(product);
        validateCategory(product);
        validateId(product); // fora da transação: numa repetição o objeto já pode ter recebido um id
        return transactions.run(() -> {
            product.setId(null);
            product.setVersion(null);
            product.setActive(true);
            ensureUnique(product);
            // Garante consistência: se stockQuantity > 0, inStock = true
            syncInStock(product);
            return persist(product);
        });
    }

    /** Falha cedo (404 / 409) antes de uma etapa cara, como o upload da imagem. A checagem que vale é a de {@link #replace}. */
    public void checkEditable(UUID id, Long expectedVersion) {
        versions.check(expectedVersion, findActiveById(id).getVersion());
    }

    /**
     * Substitui todos os campos (PUT).
     *
     * @param imageUrl imagem recém-enviada, ou nulo para usar a do corpo
     */
    public Change replace(UUID id, ProductRequestDTO dto, String imageUrl) {
        return transactions.run(() -> {
            Product product = lock(id);
            requireActive(product);
            versions.check(dto.version(), product.getVersion());

            Map<String, Object> before = AuditDiff.product(product);
            productMapper.updateEntityFromRequest(product, dto);
            if (imageUrl != null) {
                product.setImage(imageUrl);
            }
            return new Change(before, validateAndPersist(product));
        });
    }

    public Change updatePartial(UUID id, ProductUpdateDTO dto) {
        return transactions.run(() -> {
            Product product = lock(id); // aceita inativo: é assim que um produto é reativado
            versions.check(dto.getVersion(), product.getVersion());

            Map<String, Object> before = AuditDiff.product(product);
            productMapper.applyPatch(product, dto);
            return new Change(before, validateAndPersist(product));
        });
    }

    /** Exclusão lógica: os pedidos antigos continuam apontando para o produto. */
    public Product delete(UUID id) {
        return transactions.run(() -> {
            Product product = lock(id);
            requireActive(product); // 404 se já estiver inativo
            product.setActive(false);
            return productRepository.saveAndFlush(product);
        });
    }

    /**
     * Baixa o estoque de vários produtos, tudo ou nada.
     *
     * @param quantities quantidades positivas por produto; a ordem do mapa (por id) é a ordem dos locks
     */
    public void decreaseStock(SortedMap<UUID, Integer> quantities) {
        transactions.execute(() -> quantities.forEach((id, quantity) -> {
            if (!tryDecreaseStock(id, quantity)) {
                Product product = findActiveById(id);
                throw new InsufficientStockException("Estoque insuficiente para o produto: " + product.getName());
            }
        }));
    }

    /**
     * Baixa atômica no banco; quando o estoque chega a 0, marca inStock = false.
     *
     * @return false se o produto não está ativo ou não tem a quantidade
     */
    public boolean tryDecreaseStock(UUID id, int quantity) {
        if (quantity <= 0) {
            throw new InvalidProductException("Quantity must be greater than zero");
        }
        return transactions.run(() -> productRepository.decreaseStock(id, quantity, LocalDateTime.now()) == 1);
    }

    /** Estoque atual de um produto ativo (0 se não existir). */
    public int availableStock(UUID id) {
        return productRepository.findByIdAndActiveTrue(id).map(Product::getStockQuantity).orElse(0);
    }

    private Product lock(UUID id) {
        return productRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ProductNotFoundException("Product not found"));
    }

    private static void requireActive(Product product) {
        if (!Boolean.TRUE.equals(product.getActive())) {
            throw new ProductNotFoundException("Product not found");
        }
    }

    private Product validateAndPersist(Product product) {
        validateName(product);
        validatePrice(product);
        validateCategory(product);
        if (Boolean.TRUE.equals(product.getActive())) {
            ensureUnique(product);
        }
        syncInStock(product);
        return persist(product);
    }

    private Product persist(Product product) {
        try {
            return productRepository.saveAndFlush(product);
        } catch (DataIntegrityViolationException e) {
            if (String.valueOf(e.getMostSpecificCause().getMessage()).contains("uq_products_identity")) {
                throw new DuplicateProductException(DUPLICATE); // outra requisição criou o mesmo produto agora
            }
            throw e;
        }
    }

    // Mantém inStock sincronizado com stockQuantity
    private void syncInStock(Product product) {
        if (product.getStockQuantity() != null) {
            product.setInStock(product.getStockQuantity() > 0);
        }
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

    /** Um produto é identificado por nome, marca e tamanho (o índice único no banco garante sob concorrência). */
    private void ensureUnique(Product product) {
        UUID self = product.getId() == null ? NO_ID : product.getId();
        if (productRepository.existsActiveDuplicate(product.getName(), product.getBrand(), product.getSize(), self)) {
            throw new DuplicateProductException(DUPLICATE);
        }
    }

    private void validateId(Product product) {
        if (product.getId() != null) {
            throw new InvalidProductException("Product id must not be informed on creation");
        }
    }
}
