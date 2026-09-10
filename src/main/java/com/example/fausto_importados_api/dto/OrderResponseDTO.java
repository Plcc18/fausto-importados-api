package com.example.fausto_importados_api.dto;

import com.example.fausto_importados_api.model.enums.OrderStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record OrderResponseDTO(
        UUID id,
        String customerName,
        String customerWhatsapp,
        String paymentMethod,
        BigDecimal total,
        OrderStatus status,
        List<OrderItemResponseDTO> items,
        LocalDateTime createdAt
) {}
