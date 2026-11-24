package com.example.fausto_importados_api.repository;

import com.example.fausto_importados_api.model.AuthThrottle;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface AuthThrottleRepository extends JpaRepository<AuthThrottle, String> {

    /** Trava a linha para que falhas simultâneas na mesma chave sejam contadas uma a uma. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from AuthThrottle t where t.key = :key")
    Optional<AuthThrottle> findForUpdate(@Param("key") String key);

    /** Apaga contadores parados há tempo e que não estão mais bloqueados. */
    @Modifying
    @Query("""
            delete from AuthThrottle t
            where t.updatedAt < :before and (t.lockedUntil is null or t.lockedUntil < :now)
            """)
    int deleteStale(@Param("before") Instant before, @Param("now") Instant now);
}
