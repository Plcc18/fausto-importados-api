package com.example.fausto_importados_api.repository;

import com.example.fausto_importados_api.model.Campaign;
import com.example.fausto_importados_api.model.enums.CampaignStatus;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CampaignRepository extends JpaRepository<Campaign, UUID>, JpaSpecificationExecutor<Campaign> {

    /** Campanha com coleções e produtos numa única consulta; o resultado pode ser lido fora da transação. */
    @EntityGraph(attributePaths = {"collections", "collections.products"})
    @Query("select c from Campaign c where c.id = :id")
    Optional<Campaign> findDetailById(@Param("id") UUID id);

    /**
     * Campanhas com esse status que ainda não terminaram (fim exclusivo), sem coleções. Com {@code PUBLISHED}, são as
     * ativas e as agendadas.
     */
    List<Campaign> findByStatusAndEndsAtAfter(CampaignStatus status, Instant instant);

    /**
     * Campanhas publicadas com essa prioridade cujo período cruza {@code [startsAt, endsAt)}, fora a própria. Não faz
     * flush antes: a alteração pendente da campanha em edição ainda não deve ir ao banco.
     */
    @QueryHints(@QueryHint(name = "org.hibernate.flushMode", value = "COMMIT"))
    @Query("""
        select c from Campaign c
        where c.status = com.example.fausto_importados_api.model.enums.CampaignStatus.PUBLISHED
          and c.priority = :priority
          and c.startsAt < :endsAt and c.endsAt > :startsAt
          and c.id <> :excludeId
        order by c.startsAt, c.id
    """)
    List<Campaign> findPublishedOverlapping(
            @Param("priority") int priority,
            @Param("startsAt") Instant startsAt,
            @Param("endsAt") Instant endsAt,
            @Param("excludeId") UUID excludeId
    );

    /**
     * Trava a linha para alterar a campanha. Coleções e produtos são carregados quando acessados, dentro da mesma
     * transação (o PostgreSQL não aceita {@code FOR UPDATE} junto com o join das coleções).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Campaign c where c.id = :id")
    Optional<Campaign> findByIdForUpdate(@Param("id") UUID id);
}
