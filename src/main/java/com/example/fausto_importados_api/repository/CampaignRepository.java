package com.example.fausto_importados_api.repository;

import com.example.fausto_importados_api.model.Campaign;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface CampaignRepository extends JpaRepository<Campaign, UUID> {

    /** Campanha com coleções e produtos numa única consulta; o resultado pode ser lido fora da transação. */
    @EntityGraph(attributePaths = {"collections", "collections.products"})
    @Query("select c from Campaign c where c.id = :id")
    Optional<Campaign> findDetailById(@Param("id") UUID id);

    /**
     * Trava a linha para alterar a campanha. Coleções e produtos são carregados quando acessados, dentro da mesma
     * transação (o PostgreSQL não aceita {@code FOR UPDATE} junto com o join das coleções).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Campaign c where c.id = :id")
    Optional<Campaign> findByIdForUpdate(@Param("id") UUID id);
}
