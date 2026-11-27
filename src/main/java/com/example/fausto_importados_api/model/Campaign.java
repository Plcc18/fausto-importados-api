package com.example.fausto_importados_api.model;

import com.example.fausto_importados_api.model.campaign.CampaignContent;
import com.example.fausto_importados_api.model.campaign.CampaignTheme;
import com.example.fausto_importados_api.model.enums.CampaignStatus;
import com.example.fausto_importados_api.model.enums.CampaignType;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Campanha sazonal. É a raiz do agregado: coleções e produtos participantes são gravados e apagados junto com ela.
 * Não altera nenhum dado dos produtos.
 *
 * <p>Nome, tipo, datas e tema só são obrigatórios para publicar (o banco os exige em campanhas publicadas).
 *
 * <p><b>Toda alteração passa por {@link #registerChange}</b>, inclusive a que só mexe nos produtos de uma coleção:
 * é ela que faz a {@code version} subir e protege o documento inteiro contra edições simultâneas.
 */
@Entity
@Table(name = "campaigns")
@Getter
@Setter
@NoArgsConstructor
public class Campaign {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(length = 80)
    private String name;

    /** Uso interno do painel. */
    @Column(length = 500)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private CampaignType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CampaignStatus status = CampaignStatus.DRAFT;

    /** Inclusivo. */
    @Column(name = "starts_at")
    private Instant startsAt;

    /** Exclusivo. */
    @Column(name = "ends_at")
    private Instant endsAt;

    /** De 0 a 100; entre campanhas no mesmo período, a maior vence. */
    @Column(nullable = false)
    private int priority = 0;

    @JdbcTypeCode(SqlTypes.JSON)
    private CampaignTheme theme;

    @JdbcTypeCode(SqlTypes.JSON)
    private CampaignContent content;

    /** Em ordem de {@code position} quando lidas do banco. Use {@link #addCollection} para incluir. */
    @Setter(AccessLevel.NONE)
    @OneToMany(mappedBy = "campaign", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position ASC")
    @BatchSize(size = 100) // uma consulta só para as coleções de uma página inteira de campanhas
    private Set<CampaignCollection> collections = new LinkedHashSet<>();

    /** Lock otimista. */
    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    /** E-mail de quem criou. */
    @Column(name = "created_by", nullable = false, updatable = false, length = 150)
    private String createdBy;

    /** E-mail de quem alterou por último. */
    @Column(name = "updated_by", nullable = false, length = 150)
    private String updatedBy;

    public void addCollection(CampaignCollection collection) {
        collection.setCampaign(this);
        collections.add(collection);
    }

    /**
     * Registra quem alterou e quando (e, na primeira vez, quem criou).
     *
     * @param now    hora do {@code Clock} injetado
     * @param author e-mail do admin
     */
    public void registerChange(Instant now, String author) {
        if (createdAt == null) {
            createdAt = now;
            createdBy = author;
        }
        updatedAt = now;
        updatedBy = author;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Campaign campaign)) return false;
        return id != null && id.equals(campaign.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
