package com.example.fausto_importados_api.repository;

import com.example.fausto_importados_api.model.RateLimitBucket;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface RateLimitBucketRepository extends JpaRepository<RateLimitBucket, String> {

    /** Trava a linha: requisições simultâneas na mesma chave gastam fichas uma a uma. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from RateLimitBucket b where b.key = :key")
    Optional<RateLimitBucket> findForUpdate(@Param("key") String key);

    @Modifying
    @Query("delete from RateLimitBucket b where b.updatedMs < :before")
    int deleteIdleBefore(@Param("before") long before);
}
