package com.example.fausto_importados_api.repository;

import com.example.fausto_importados_api.model.Product;
import com.example.fausto_importados_api.model.enums.Category;
import com.example.fausto_importados_api.model.enums.OlfactiveFamily;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;


import java.util.Optional;
import java.util.UUID;

public interface ProductRepository extends JpaRepository<Product, UUID> {

    // Buscar produto ativo por ID
    Optional<Product> findByIdAndActiveTrue(UUID id);

    // Listar produtos ativos
    Page<Product> findAllByActiveTrue(Pageable pageable);

    // Produtos em destaque ativos
    Page<Product> findByFeaturedTrueAndActiveTrue(Pageable pageable);

    // Filtro por categoria
    Page<Product> findByCategoryAndActiveTrue(
            Category category,
            Pageable pageable
    );

    // Filtro por família olfativa
    Page<Product> findByOlfactiveFamilyAndActiveTrue(
            OlfactiveFamily olfactiveFamily,
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
}

