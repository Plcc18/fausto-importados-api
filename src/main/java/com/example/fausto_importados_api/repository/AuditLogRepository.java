package com.example.fausto_importados_api.repository;

import com.example.fausto_importados_api.model.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

    Page<AuditLog> findAllByOrderByOccurredAtDesc(Pageable pageable);

    Page<AuditLog> findByActionOrderByOccurredAtDesc(String action, Pageable pageable);

    Page<AuditLog> findByEntityTypeOrderByOccurredAtDesc(String entityType, Pageable pageable);

    Page<AuditLog> findByActionAndEntityTypeOrderByOccurredAtDesc(String action, String entityType, Pageable pageable);

    @Modifying
    @Query("delete from AuditLog a where a.occurredAt < :before")
    int deleteOlderThan(@Param("before") Instant before);
}
