package com.example.fausto_importados_api.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Balde do rate limit guardado no Postgres (usado quando não há Redis). */
@Entity
@Table(name = "rate_limit_bucket")
@Getter
@Setter
@NoArgsConstructor
public class RateLimitBucket {

    @Id
    @Column(name = "bucket_key", length = 120)
    private String key;

    @Column(nullable = false)
    private double tokens;

    @Column(name = "updated_ms", nullable = false)
    private long updatedMs;

    public RateLimitBucket(String key, double tokens, long updatedMs) {
        this.key = key;
        this.tokens = tokens;
        this.updatedMs = updatedMs;
    }
}
