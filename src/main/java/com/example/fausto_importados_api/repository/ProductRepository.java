package com.example.fausto_importados_api.repository;

import com.example.fausto_importados_api.model.Product;
import com.example.fausto_importados_api.model.enums.Category;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import com.example.fausto_importados_api.model.enums.OlfactiveFamily;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;


import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProductRepository extends JpaRepository<Product, UUID> {

    // Buscar produto ativo por ID
    Optional<Product> findByIdAndActiveTrue(UUID id);

    // Buscar vários produtos ativos de uma vez (evita N+1 ao validar itens de um pedido)
    List<Product> findAllByIdInAndActiveTrue(Collection<UUID> ids);

    // Listar produtos ativos
    Page<Product> findAllByActiveTrue(Pageable pageable);

    // Produtos em destaque ativos
    Page<Product> findByFeaturedTrueAndActiveTrue(Pageable pageable);

    // Filtro por categoria
    Page<Product> findByCategoryAndActiveTrue(
            Category category,
            Pageable pageable
    );

    // Filtro por família olfativa (com suporte a múltiplas famílias)
    @Query("""
        SELECT p FROM Product p
        WHERE p.active = true
          AND LOWER(p.olfactiveFamily) LIKE LOWER(CONCAT('%', :olfactiveFamily, '%'))
    """)
    Page<Product> findByOlfactiveFamilyAndActiveTrue(
            @Param("olfactiveFamily") String olfactiveFamily,
            Pageable pageable
    );

    // Busca por nome ou marca
    @Query("""
        SELECT p FROM Product p
        WHERE p.active = true
          AND (
            LOWER(p.name) LIKE LOWER(CONCAT('%', :term, '%'))
            OR LOWER(p.brand) LIKE LOWER(CONCAT('%', :term, '%'))
          )
    """)
    Page<Product> searchActiveByNameOrBrand(
            @Param("term") String term,
            Pageable pageable
    );

    /** Trava a linha para editar: edições e vendas do mesmo produto acontecem uma de cada vez. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Product p where p.id = :id")
    Optional<Product> findByIdForUpdate(@Param("id") UUID id);

    /**
     * Já existe OUTRO produto ativo com o mesmo nome, marca e tamanho? Não faz flush antes (a alteração pendente
     * do produto em edição ainda não deve ir ao banco).
     */
    @QueryHints(@QueryHint(name = "org.hibernate.flushMode", value = "COMMIT"))
    @Query("""
        select count(p) > 0 from Product p
        where p.active = true
          and lower(trim(p.name)) = lower(trim(:name))
          and lower(trim(p.brand)) = lower(trim(:brand))
          and lower(trim(p.size)) = lower(trim(:size))
          and p.id <> :excludeId
    """)
    boolean existsActiveDuplicate(
            @Param("name") String name,
            @Param("brand") String brand,
            @Param("size") String size,
            @Param("excludeId") UUID excludeId
    );

    /**
     * Baixa atômica: o banco confere e subtrai no mesmo comando, então duas vendas simultâneas nunca usam o mesmo
     * saldo nem o deixam negativo.
     *
     * @return 1 se baixou; 0 se o produto não existe, está inativo ou não tem estoque suficiente
     */
    @Modifying(flushAutomatically = true)
    @Query("""
        update Product p
           set p.stockQuantity = p.stockQuantity - :quantity,
               p.inStock = case when p.stockQuantity > :quantity then true else false end,
               p.version = p.version + 1,
               p.updatedAt = :now
         where p.id = :id and p.active = true and p.stockQuantity >= :quantity
    """)
    int decreaseStock(@Param("id") UUID id, @Param("quantity") int quantity, @Param("now") LocalDateTime now);
}

