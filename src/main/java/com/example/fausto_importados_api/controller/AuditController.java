package com.example.fausto_importados_api.controller;

import com.example.fausto_importados_api.model.AuditLog;
import com.example.fausto_importados_api.repository.AuditLogRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PagedModel;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/** Consulta (somente leitura) da trilha de auditoria, mais recente primeiro. Apenas administradores. */
@RestController
@RequestMapping("/api/admin/audit")
public class AuditController {

    private static final int MAX_PAGE_SIZE = 200;

    public record Entry(
            UUID id, Instant occurredAt, UUID actorId, String actorEmail, String action,
            String entityType, String entityId, String outcome, String ip, String userAgent, String details
    ) {
        static Entry from(AuditLog row) {
            return new Entry(row.getId(), row.getOccurredAt(), row.getActorId(), row.getActorEmail(), row.getAction(),
                    row.getEntityType(), row.getEntityId(), row.getOutcome().name(), row.getIp(), row.getUserAgent(),
                    row.getDetails());
        }
    }

    private final AuditLogRepository repository;

    public AuditController(AuditLogRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PagedModel<Entry>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String entityType
    ) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE));
        boolean byAction = action != null && !action.isBlank();
        boolean byEntity = entityType != null && !entityType.isBlank();

        Page<AuditLog> rows;
        if (byAction && byEntity) {
            rows = repository.findByActionAndEntityTypeOrderByOccurredAtDesc(action, entityType, pageable);
        } else if (byAction) {
            rows = repository.findByActionOrderByOccurredAtDesc(action, pageable);
        } else if (byEntity) {
            rows = repository.findByEntityTypeOrderByOccurredAtDesc(entityType, pageable);
        } else {
            rows = repository.findAllByOrderByOccurredAtDesc(pageable);
        }
        return ResponseEntity.ok(new PagedModel<>(rows.map(Entry::from)));
    }
}
