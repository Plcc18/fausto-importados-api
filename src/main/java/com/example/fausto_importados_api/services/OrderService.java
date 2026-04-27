package com.example.fausto_importados_api.services;

import com.example.fausto_importados_api.dto.CreateOrderDTO;
import com.example.fausto_importados_api.dto.SalesStatsDTO;
import com.example.fausto_importados_api.model.Order;
import com.example.fausto_importados_api.model.OrderItem;
import com.example.fausto_importados_api.model.enums.OrderStatus;
import com.example.fausto_importados_api.repository.OrderRepository;
import com.example.fausto_importados_api.services.exception.BusinessException;
import com.example.fausto_importados_api.services.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final ProductService productService;

    public OrderService(OrderRepository orderRepository, ProductService productService) {
        this.orderRepository = orderRepository;
        this.productService = productService;
    }

    @Transactional
    public Order createOrder(CreateOrderDTO dto) {
        Order order = new Order();
        order.setCustomerName(dto.customerName());
        order.setCustomerWhatsapp(dto.customerWhatsapp());
        order.setPaymentMethod(dto.paymentMethod());
        order.setTotal(dto.total());
        order.setStatus(OrderStatus.PENDING);

        for (CreateOrderDTO.ItemDTO itemDTO : dto.items()) {
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
            order.getItems().add(item);
        }

        return orderRepository.save(order);
    }

    // For notifications panel — shows all orders regardless of hidden flags
    public List<Order> findAll() {
        return orderRepository.findAllByOrderByCreatedAtDesc();
    }

    // For notifications panel filter — shows by status regardless of hidden flags
    public List<Order> findByStatus(OrderStatus status) {
        return orderRepository.findByStatusOrderByCreatedAtDesc(status);
    }

    // For sales stats cards in admin panel — excludes hiddenFromPanel
    public List<Order> findCompletedForPanel() {
        return orderRepository.findByStatusAndHiddenFromPanelFalseOrderByCreatedAtDesc(OrderStatus.COMPLETED);
    }

    // For sales report page — excludes hiddenFromReport
    public List<Order> findCompletedForReport() {
        return orderRepository.findByStatusAndHiddenFromReportFalseOrderByCreatedAtDesc(OrderStatus.COMPLETED);
    }

    @Transactional
    public Order complete(UUID orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));

        if (order.getStatus() != OrderStatus.PENDING)
            throw new IllegalStateException("Only PENDING orders can be completed");

        // Check stock for all items before decrementing
        for (OrderItem item : order.getItems()) {
            var product = productService.findActiveById(item.getProductId());
            if ((product.getStockQuantity() == null ? 0 : product.getStockQuantity()) < item.getQuantity()) {
                throw new BusinessException(
                        "Estoque insuficiente para \"" + item.getProductName() + "\" (" + item.getProductSize() + "ml). " +
                                "Disponível: " + (product.getStockQuantity() == null ? 0 : product.getStockQuantity()) + " | Pedido: " + item.getQuantity()
                );
            }
        }

        for (OrderItem item : order.getItems())
            productService.decreaseStock(item.getProductId(), item.getQuantity());

        order.setStatus(OrderStatus.COMPLETED);
        return orderRepository.save(order);
    }

    @Transactional
    public Order cancel(UUID orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));

        if (order.getStatus() != OrderStatus.PENDING)
            throw new IllegalStateException("Only PENDING orders can be cancelled");

        order.setStatus(OrderStatus.CANCELLED);
        return orderRepository.save(order);
    }

    @Transactional
    public void clearHistory() {
        orderRepository.deleteByStatusIn(List.of(OrderStatus.COMPLETED, OrderStatus.CANCELLED));
    }

    // Sales stats for admin panel — only orders visible in panel
    public SalesStatsDTO getSalesStats() {
        LocalDate today = LocalDate.now();
        LocalDateTime startOfDay = today.atStartOfDay();
        LocalDateTime startOfMonth = today.withDayOfMonth(1).atStartOfDay();
        LocalDateTime startOfYear = today.withDayOfYear(1).atStartOfDay();

        List<Order> completed = findCompletedForPanel();

        return new SalesStatsDTO(
                sum(completed, startOfDay).doubleValue(),
                sum(completed, startOfMonth).doubleValue(),
                sum(completed, startOfYear).doubleValue()
        );
    }

    private BigDecimal sum(List<Order> orders, LocalDateTime from) {
        return orders.stream()
                .filter(o -> !o.getCreatedAt().isBefore(from))
                .map(Order::getTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // Reset panel stats — hides from panel only, report keeps its data
    @Transactional
    public void resetSales() {
        orderRepository.hideCompletedFromPanel();
    }

    // Reset report — hides from report only, panel keeps its data
    @Transactional
    public void resetReport() {
        orderRepository.hideCompletedFromReport();
    }
}