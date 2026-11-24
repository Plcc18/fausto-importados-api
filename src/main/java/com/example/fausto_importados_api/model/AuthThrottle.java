package com.example.fausto_importados_api.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** Contador de falhas de login de uma chave (conta ou IP), com o fim do bloqueio temporário. */
@Entity
@Table(name = "auth_throttle")
@Getter
@Setter
@NoArgsConstructor
public class AuthThrottle {

    @Id
    @Column(name = "throttle_key", length = 80)
    private String key;

    @Column(nullable = false)
    private int failures;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public AuthThrottle(String key, Instant now) {
        this.key = key;
        this.updatedAt = now;
    }
}
