package com.example.fausto_importados_api.services;

import com.example.fausto_importados_api.concurrency.Transactions;
import com.example.fausto_importados_api.dto.CreateOrderDTO;
import com.example.fausto_importados_api.dto.SalesStatsDTO;
import com.example.fausto_importados_api.mapper.OrderMapper;
import com.example.fausto_importados_api.model.Order;
import com.example.fausto_importados_api.model.OrderItem;
import com.example.fausto_importados_api.model.Product;
import com.example.fausto_importados_api.model.enums.OrderStatus;
import com.example.fausto_importados_api.repository.OrderRepository;
import com.example.fausto_importados_api.services.exception.InsufficientStockException;
import com.example.fausto_importados_api.services.exception.InvalidOrderException;
import com.example.fausto_importados_api.services.exception.InvalidOrderStatusException;
import com.example.fausto_importados_api.services.exception.OrderNotFoundException;
import org.hibernate.Hibernate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final ProductService productService;
    private final OrderMapper orderMapper;
    private final Transactions transactions;

    public OrderService(
            OrderRepository orderRepository,
            ProductService productService,
            OrderMapper orderMapper,
            Transactions transactions
    ) {
        this.orderRepository = orderRepository;
        this.productService = productService;
        this.orderMapper = orderMapper;
        this.transactions = transactions;
    }

    /**
     * Cria um pedido PENDING. Nomes, preços e total vêm do cadastro dos produtos, nunca do cliente; do pedido
     * recebido valem o produto, a quantidade e os dados de contato.
     */
    public Order createOrder(CreateOrderDTO dto) {
        return transactions.run(() -> {
            List<UUID> ids = dto.items().stream().map(CreateOrderDTO.ItemDTO::productId).distinct().toList();
            Map<UUID, Product> products = productService.findAllActiveByIds(ids).stream()
                    .collect(Collectors.toMap(Product::getId, Function.identity()));
            if (products.size() != ids.size()) {
                throw new InvalidOrderException("Um dos produtos do pedido não está mais disponível.");
            }
            return orderRepository.saveAndFlush(orderMapper.toEntity(dto, products));
        });
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

    /**
     * Conclui o pedido e baixa o estoque, tudo ou nada. O pedido fica travado (um segundo clique espera e encontra
     * o status já alterado) e cada baixa é um update atômico, em ordem de id de produto.
     */
    public Order complete(UUID orderId) {
        return transactions.run(() -> {
            Order order = lockPending(orderId, "Only PENDING orders can be completed");

            // Soma as quantidades por produto (caso o mesmo produto apareça em mais de um item)
            SortedMap<UUID, Integer> quantities = order.getItems().stream()
                    .collect(Collectors.toMap(
                            OrderItem::getProductId, OrderItem::getQuantity, Math::addExact, TreeMap::new));

            quantities.forEach((productId, quantity) -> {
                if (quantity <= 0) {
                    throw new InvalidOrderException("O pedido tem um item com quantidade inválida.");
                }
                if (!productService.tryDecreaseStock(productId, quantity)) {
                    throw insufficientStock(order, productId, quantity);
                }
            });

            order.setStatus(OrderStatus.COMPLETED);
            return orderRepository.save(order);
        });
    }

    public Order cancel(UUID orderId) {
        return transactions.run(() -> {
            Order order = lockPending(orderId, "Only PENDING orders can be cancelled");
            Hibernate.initialize(order.getItems()); // a resposta é montada fora da transação
            order.setStatus(OrderStatus.CANCELLED);
            return orderRepository.save(order);
        });
    }

    public void clearHistory() {
        transactions.execute(orderRepository::hideCompletedAndCancelledFromNotifications);
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
    public void resetSales() {
        transactions.execute(orderRepository::hideCompletedFromPanel);
    }

    // Reset report — hides from report only, panel keeps its data
    public void resetReport() {
        transactions.execute(orderRepository::hideCompletedFromReport);
    }

    private Order lockPending(UUID orderId, String notPendingMessage) {
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new OrderNotFoundException("Order not found"));
        if (order.getStatus() != OrderStatus.PENDING) {
            throw new InvalidOrderStatusException(notPendingMessage);
        }
        return order;
    }

    private InsufficientStockException insufficientStock(Order order, UUID productId, int quantity) {
        OrderItem item = order.getItems().stream()
                .filter(i -> i.getProductId().equals(productId))
                .findFirst().orElseThrow();
        return new InsufficientStockException(
                "Estoque insuficiente para \"" + item.getProductName() + "\" (" + item.getProductSize() + "ml). " +
                        "Disponível: " + productService.availableStock(productId) + " | Pedido: " + quantity);
    }
}
