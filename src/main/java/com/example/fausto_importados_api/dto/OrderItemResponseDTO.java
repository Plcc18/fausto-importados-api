package com.example.fausto_importados_api.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record OrderItemResponseDTO(
        UUID id,
        UUID productId,
        String productName,
        String productSize,
        String productCategory,
        String productFamily,
        Boolean onSale,
        Integer quantity,
        BigDecimal unitPrice
) {}
