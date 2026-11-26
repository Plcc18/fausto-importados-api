package com.example.fausto_importados_api.repository;

import com.example.fausto_importados_api.model.RefreshToken;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    /**
     * Busca o token por hash travando a linha (SELECT ... FOR UPDATE). Duas requisições com o mesmo
     * token são serializadas: a segunda só lê depois que a primeira confirmou a rotação.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from RefreshToken t where t.tokenHash = :hash")
    Optional<RefreshToken> findByTokenHashForUpdate(@Param("hash") String hash);

    /** Dono do token, sem carregar nem travar a linha: o usuário precisa ser travado ANTES do token. */
    @Query("select t.userId from RefreshToken t where t.tokenHash = :hash")
    Optional<UUID> findUserIdByTokenHash(@Param("hash") String hash);

    @Query("select distinct t.userId from RefreshToken t where t.familyId = :familyId")
    List<UUID> findUserIdsByFamilyId(@Param("familyId") UUID familyId);

    /** A sessão (família) continua válida? Usado a cada request autenticado, via claim "sid". */
    @Query("""
            select count(t) > 0 from RefreshToken t
            where t.familyId = :familyId
              and t.revokedAt is null
              and t.absoluteExpiresAt > :now
            """)
    boolean isSessionActive(@Param("familyId") UUID familyId, @Param("now") Instant now);

    @Modifying
    @Query("update RefreshToken t set t.revokedAt = :now where t.familyId = :familyId and t.revokedAt is null")
    int revokeFamily(@Param("familyId") UUID familyId, @Param("now") Instant now);

    @Modifying
    @Query("update RefreshToken t set t.revokedAt = :now where t.userId = :userId and t.revokedAt is null")
    int revokeAllByUser(@Param("userId") UUID userId, @Param("now") Instant now);

    @Modifying
    @Query("update RefreshToken t set t.revokedAt = :now where t.userId = :userId and t.familyId <> :keep and t.revokedAt is null")
    int revokeAllByUserExcept(@Param("userId") UUID userId, @Param("keep") UUID keepFamilyId, @Param("now") Instant now);

    @Modifying
    @Query("delete from RefreshToken t where t.expiresAt < :now or t.absoluteExpiresAt < :now")
    int deleteExpired(@Param("now") Instant now);

    long countByFamilyId(UUID familyId);
}
