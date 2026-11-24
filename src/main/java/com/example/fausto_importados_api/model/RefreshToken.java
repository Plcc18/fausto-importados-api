package com.example.fausto_importados_api.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Um refresh token emitido. Guarda apenas o hash (SHA-256) do valor entregue ao browser.
 * Tokens da mesma sessão de login compartilham o {@code familyId}.
 */
@Entity
@Table(name = "refresh_tokens")
@Getter
@Setter
@NoArgsConstructor
public class RefreshToken {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "family_id", nullable = false)
    private UUID familyId;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_used_at", nullable = false)
    private Instant lastUsedAt;

    /** Prazo por inatividade — renovado a cada refresh, nunca ultrapassa {@link #absoluteExpiresAt}. */
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** Limite absoluto da sessão: não é renovado. */
    @Column(name = "absolute_expires_at", nullable = false)
    private Instant absoluteExpiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    /** Quando este token foi trocado por outro (rotação). Se for apresentado depois disso, é reuso. */
    @Column(name = "rotated_at")
    private Instant rotatedAt;

    @Column(name = "replaced_by_id")
    private UUID replacedById;

    @Column(name = "user_agent", length = 255)
    private String userAgent;

    @Column(name = "ip", length = 64)
    private String ip;
}
