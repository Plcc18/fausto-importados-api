package com.example.fausto_importados_api.mapper;

import com.example.fausto_importados_api.dto.CreateOrderDTO;
import com.example.fausto_importados_api.dto.OrderItemResponseDTO;
import com.example.fausto_importados_api.dto.OrderResponseDTO;
import com.example.fausto_importados_api.model.Order;
import com.example.fausto_importados_api.model.OrderItem;
import com.example.fausto_importados_api.model.Product;
import com.example.fausto_importados_api.model.enums.OrderStatus;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Component
public class OrderMapper {

    /** @param products produtos ativos referenciados pelo pedido: deles vêm nomes, preços e o total */
    public Order toEntity(CreateOrderDTO dto, Map<UUID, Product> products) {
        Order order = new Order();
        order.setCustomerName(dto.customerName());
        order.setCustomerWhatsapp(dto.customerWhatsapp());
        order.setPaymentMethod(dto.paymentMethod());
        order.setStatus(OrderStatus.PENDING);

        dto.items().stream()
                .map(itemDTO -> toItemEntity(itemDTO, products.get(itemDTO.productId()), order))
                .forEach(order.getItems()::add);

        order.setTotal(order.getItems().stream()
                .map(item -> item.getUnitPrice().multiply(BigDecimal.valueOf(item.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        return order;
    }

    // Retrato do produto no momento da venda: o relatório não muda se o produto for editado depois
    private OrderItem toItemEntity(CreateOrderDTO.ItemDTO itemDTO, Product product, Order order) {
        OrderItem item = new OrderItem();
        item.setOrder(order);
        item.setProductId(product.getId());
        item.setProductName(product.getName());
        item.setProductSize(product.getSize());
        item.setProductCategory(product.getCategory().name().toUpperCase(Locale.ROOT));
        item.setProductFamily(product.getOlfactiveFamily().toUpperCase(Locale.ROOT));
        item.setOnSale(product.getOriginalPrice() != null
                && product.getOriginalPrice().compareTo(product.getPrice()) > 0);
        item.setQuantity(itemDTO.quantity());
        item.setUnitPrice(product.getPrice());
        return item;
    }

    public OrderResponseDTO toResponseDTO(Order order) {
        return new OrderResponseDTO(
                order.getId(),
                order.getCustomerName(),
                order.getCustomerWhatsapp(),
                order.getPaymentMethod(),
                order.getTotal(),
                order.getStatus(),
                order.getItems().stream().map(this::toItemResponseDTO).toList(),
                order.getCreatedAt()
        );
    }

    private OrderItemResponseDTO toItemResponseDTO(OrderItem item) {
        return new OrderItemResponseDTO(
                item.getId(),
                item.getProductId(),
                item.getProductName(),
                item.getProductSize(),
                item.getProductCategory(),
                item.getProductFamily(),
                item.getOnSale(),
                item.getQuantity(),
                item.getUnitPrice()
        );
    }
}
