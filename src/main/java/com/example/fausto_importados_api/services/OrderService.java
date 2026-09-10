package com.example.fausto_importados_api.services;

import com.example.fausto_importados_api.dto.SalesStatsDTO;
import com.example.fausto_importados_api.model.Order;
import com.example.fausto_importados_api.model.OrderItem;
import com.example.fausto_importados_api.model.Product;
import com.example.fausto_importados_api.model.enums.OrderStatus;
import com.example.fausto_importados_api.repository.OrderRepository;
import com.example.fausto_importados_api.services.exception.InsufficientStockException;
import com.example.fausto_importados_api.services.exception.InvalidOrderStatusException;
import com.example.fausto_importados_api.services.exception.OrderNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final ProductService productService;

    public OrderService(OrderRepository orderRepository, ProductService productService) {
        this.orderRepository = orderRepository;
        this.productService = productService;
    }

    @Transactional
    public Order createOrder(Order order) {
        return orderRepository.save(order);
    }

    // For notifications panel — shows all orders regardless of hidden flags
    public List<Order> findAll() {
        return orderRepository.findByHiddenFromNotificationsFalseOrderByCreatedAtDesc();
    }

    // For notifications panel filter — shows by status regardless of hidden flags
    public List<Order> findByStatus(OrderStatus status) {
        return orderRepository.findByStatusAndHiddenFromNotificationsFalseOrderByCreatedAtDesc(status);
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
                .orElseThrow(() -> new OrderNotFoundException("Order not found"));

        if (order.getStatus() != OrderStatus.PENDING)
            throw new InvalidOrderStatusException("Only PENDING orders can be completed");

        // Soma as quantidades por produto (caso o mesmo produto apareça em mais de um item)
        Map<UUID, Integer> quantitiesByProductId = order.getItems().stream()
                .collect(Collectors.toMap(OrderItem::getProductId, OrderItem::getQuantity, Integer::sum));

        // Busca todos os produtos numa única query, em vez de uma consulta por item (N+1)
        Map<UUID, Product> productsById = productService.findAllActiveByIds(quantitiesByProductId.keySet()).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));

        order.getItems().forEach(item -> {
            Product product = productsById.get(item.getProductId());
            int available = product == null || product.getStockQuantity() == null ? 0 : product.getStockQuantity();

            if (available < item.getQuantity()) {
                throw new InsufficientStockException(
                        "Estoque insuficiente para \"" + item.getProductName() + "\" (" + item.getProductSize() + "ml). " +
                                "Disponível: " + available + " | Pedido: " + item.getQuantity()
                );
            }
        });

        productService.decreaseStockBatch(quantitiesByProductId);

        order.setStatus(OrderStatus.COMPLETED);
        return orderRepository.save(order);
    }

    @Transactional
    public Order cancel(UUID orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException("Order not found"));

        if (order.getStatus() != OrderStatus.PENDING)
            throw new InvalidOrderStatusException("Only PENDING orders can be cancelled");

        order.setStatus(OrderStatus.CANCELLED);
        return orderRepository.save(order);
    }

    @Transactional
    public void clearHistory() {
        orderRepository.hideCompletedAndCancelledFromNotifications();
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