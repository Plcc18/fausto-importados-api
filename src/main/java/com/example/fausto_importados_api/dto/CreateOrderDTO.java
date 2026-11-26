package com.example.fausto_importados_api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Pedido enviado pela loja. {@code total}, preços, nomes e categorias são aceitos por compatibilidade, mas
 * ignorados: o servidor usa o cadastro dos produtos.
 */
public record CreateOrderDTO(
        @NotBlank @Size(max = 255) String customerName,
        @NotBlank @Size(max = 255) String customerWhatsapp,
        @NotBlank @Size(max = 50) String paymentMethod,
        BigDecimal total,
        @NotEmpty @Size(max = 100) List<@NotNull @Valid ItemDTO> items
) {
    public record ItemDTO(
            @NotNull UUID productId,
            String productName,
            String productSize,
            String productCategory,
            String productFamily,
            Boolean onSale,
            @NotNull @Min(1) @Max(1000) Integer quantity,
            BigDecimal unitPrice
    ) {}
}
