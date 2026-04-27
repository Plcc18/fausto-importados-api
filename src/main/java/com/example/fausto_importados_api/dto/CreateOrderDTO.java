package com.example.fausto_importados_api.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record CreateOrderDTO(
        String customerName,
        String customerWhatsapp,
        String paymentMethod,
        BigDecimal total,
        List<ItemDTO> items
) {
    public record ItemDTO(
            UUID productId,
            String productName,
            String productSize,
            String productCategory,
            String productFamily,
            Boolean onSale,
            Integer quantity,
            BigDecimal unitPrice
    ) {}
}