package com.example.fausto_importados_api.model.enums;

public enum OrderStatus {
    PENDING,    // Aguardando confirmação do admin
    COMPLETED,  // Admin confirmou e descontou estoque
    CANCELLED   // Admin cancelou (cliente não pagou)
}