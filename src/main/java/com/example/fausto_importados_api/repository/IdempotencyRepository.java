package com.example.fausto_importados_api.repository;

import com.example.fausto_importados_api.model.IdempotencyRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface IdempotencyRepository extends JpaRepository<IdempotencyRecord, IdempotencyRecord.Key> {

    /**
     * INSERT de verdade (nunca {@code save}/{@code merge}): é a chave primária que decide quem executa. Se outra
     * transação inseriu a mesma chave e ainda não confirmou, o banco espera por ela.
     */
    @Modifying
    @Query(value = """
            insert into idempotency_keys (op_scope, idem_key, request_hash, created_at)
            values (:scope, :key, :hash, :now)
            """, nativeQuery = true)
    void claim(@Param("scope") String scope, @Param("key") String key, @Param("hash") String hash,
               @Param("now") Instant now);

    @Modifying
    @Query("""
            update IdempotencyRecord r set r.responseStatus = :status, r.responseBody = :body
            where r.scope = :scope and r.key = :key
            """)
    int complete(@Param("scope") String scope, @Param("key") String key, @Param("status") int status,
                 @Param("body") String body);

    @Modifying
    @Query("delete from IdempotencyRecord r where r.createdAt < :before")
    int deleteOlderThan(@Param("before") Instant before);
}
