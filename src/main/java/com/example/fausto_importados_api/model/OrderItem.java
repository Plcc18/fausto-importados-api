package com.example.fausto_importados_api.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "order_items")
@Data
public class OrderItem {

    @Id
    @GeneratedValue
    private UUID id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Column(nullable = false)
    private UUID productId;

    @Column(nullable = false)
    private String productName;

    @Column(nullable = false)
    private String productSize;

    // Categoria e família olfativa salvas no momento da venda
    // para que o relatório possa filtrar mesmo que o produto seja editado depois
    @Column
    private String productCategory;

    @Column
    private String productFamily;

    // true se o produto estava em promoção no momento da compra
    @Column(nullable = false)
    private Boolean onSale = false;

    @Column(nullable = false)
    private Integer quantity;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal unitPrice;
}