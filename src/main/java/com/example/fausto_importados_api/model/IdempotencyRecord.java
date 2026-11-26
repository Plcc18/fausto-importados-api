package com.example.fausto_importados_api.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.time.Instant;

/** Chave de idempotência já usada e a resposta que ela produziu (ver {@code IdempotencyService}). */
@Entity
@Table(name = "idempotency_keys")
@IdClass(IdempotencyRecord.Key.class)
@Getter
@Setter
@NoArgsConstructor
public class IdempotencyRecord {

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Key implements Serializable {
        private String scope;
        private String key;
    }

    /** Operação e autor. */
    @Id
    @Column(name = "op_scope", length = 200)
    private String scope;

    @Id
    @Column(name = "idem_key", length = 100)
    private String key;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "response_status")
    private Integer responseStatus;

    @Column(name = "response_body", columnDefinition = "TEXT")
    private String responseBody;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
