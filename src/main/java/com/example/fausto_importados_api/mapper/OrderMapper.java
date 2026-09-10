package com.example.fausto_importados_api.mapper;

import com.example.fausto_importados_api.dto.CreateOrderDTO;
import com.example.fausto_importados_api.dto.OrderItemResponseDTO;
import com.example.fausto_importados_api.dto.OrderResponseDTO;
import com.example.fausto_importados_api.model.Order;
import com.example.fausto_importados_api.model.OrderItem;
import com.example.fausto_importados_api.model.enums.OrderStatus;
import org.springframework.stereotype.Component;

@Component
public class OrderMapper {

    public Order toEntity(CreateOrderDTO dto) {
        Order order = new Order();
        order.setCustomerName(dto.customerName());
        order.setCustomerWhatsapp(dto.customerWhatsapp());
        order.setPaymentMethod(dto.paymentMethod());
        order.setTotal(dto.total());
        order.setStatus(OrderStatus.PENDING);

        dto.items().stream()
                .map(itemDTO -> toItemEntity(itemDTO, order))
                .forEach(order.getItems()::add);

        return order;
    }

    private OrderItem toItemEntity(CreateOrderDTO.ItemDTO itemDTO, Order order) {
        OrderItem item = new OrderItem();
        item.setOrder(order);
        item.setProductId(itemDTO.productId());
        item.setProductName(itemDTO.productName());
        item.setProductSize(itemDTO.productSize());
        item.setProductCategory(itemDTO.productCategory());
        item.setProductFamily(itemDTO.productFamily());
        item.setOnSale(itemDTO.onSale() != null ? itemDTO.onSale() : false);
        item.setQuantity(itemDTO.quantity());
        item.setUnitPrice(itemDTO.unitPrice());
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
