package com.example.fausto_importados_api.repository;

import com.example.fausto_importados_api.model.MfaRecoveryCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

public interface MfaRecoveryCodeRepository extends JpaRepository<MfaRecoveryCode, UUID> {

    /** Consome o código de forma atômica: só quem marcar {@code used_at} primeiro o usa. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update MfaRecoveryCode c set c.usedAt = :now
            where c.userId = :userId and c.codeHash = :hash and c.usedAt is null
            """)
    int consume(@Param("userId") UUID userId, @Param("hash") String hash, @Param("now") Instant now);

    long countByUserIdAndUsedAtIsNull(UUID userId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from MfaRecoveryCode c where c.userId = :userId")
    int deleteAllByUser(@Param("userId") UUID userId);
}
