package com.example.fausto_importados_api.controller;

import com.example.fausto_importados_api.audit.AuditAction;
import com.example.fausto_importados_api.audit.AuditService;
import com.example.fausto_importados_api.dto.CreateOrderDTO;
import com.example.fausto_importados_api.dto.OrderResponseDTO;
import com.example.fausto_importados_api.dto.SalesStatsDTO;
import com.example.fausto_importados_api.mapper.OrderMapper;
import com.example.fausto_importados_api.model.Order;
import com.example.fausto_importados_api.model.enums.OrderStatus;
import com.example.fausto_importados_api.security.reauth.RequiresReauth;
import com.example.fausto_importados_api.services.OrderService;
import com.example.fausto_importados_api.services.exception.BusinessException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;
    private final OrderMapper orderMapper;
    private final AuditService audit;

    public OrderController(OrderService orderService, OrderMapper orderMapper, AuditService audit) {
        this.orderService = orderService;
        this.orderMapper = orderMapper;
        this.audit = audit;
    }

    @PostMapping
    public ResponseEntity<OrderResponseDTO> createOrder(@RequestBody CreateOrderDTO dto) {
        var order = orderService.createOrder(orderMapper.toEntity(dto));
        return ResponseEntity.ok(orderMapper.toResponseDTO(order));
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<OrderResponseDTO>> getAllOrders() {
        return ResponseEntity.ok(orderService.findAll().stream().map(orderMapper::toResponseDTO).toList());
    }

    @GetMapping("/filter")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<OrderResponseDTO>> getByStatus(@RequestParam OrderStatus status) {
        return ResponseEntity.ok(orderService.findByStatus(status).stream().map(orderMapper::toResponseDTO).toList());
    }

    // Report page — only orders not hidden from report
    @GetMapping("/report")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<OrderResponseDTO>> getForReport() {
        return ResponseEntity.ok(orderService.findCompletedForReport().stream().map(orderMapper::toResponseDTO).toList());
    }

    @GetMapping("/sales-stats")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<SalesStatsDTO> getSalesStats() {
        return ResponseEntity.ok(orderService.getSalesStats());
    }

    @PostMapping("/{id}/complete")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> completeOrder(@PathVariable UUID id) {
        try {
            Order completed = orderService.complete(id);
            audit.recordSuccess(AuditAction.ORDER_COMPLETED, "ORDER", id, Map.of(
                    "total", String.valueOf(completed.getTotal()),
                    "items", completed.getItems().stream()
                            .map(item -> Map.of("productId", String.valueOf(item.getProductId()), "quantity", item.getQuantity()))
                            .toList()));
            return ResponseEntity.ok(orderMapper.toResponseDTO(completed));
        } catch (BusinessException e) {
            // Returns 409 with the stock error message so the frontend can show it
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<OrderResponseDTO> cancelOrder(@PathVariable UUID id) {
        Order cancelled = orderService.cancel(id);
        audit.recordSuccess(AuditAction.ORDER_CANCELLED, "ORDER", id, null);
        return ResponseEntity.ok(orderMapper.toResponseDTO(cancelled));
    }

    @DeleteMapping("/clear-history")
    @PreAuthorize("hasRole('ADMIN')")
    @RequiresReauth
    public ResponseEntity<Void> clearHistory() {
        orderService.clearHistory();
        audit.recordSuccess(AuditAction.ORDERS_HISTORY_CLEARED, "ORDER", null, null);
        return ResponseEntity.noContent().build();
    }

    // Hides from admin panel stats only
    @DeleteMapping("/reset-sales")
    @PreAuthorize("hasRole('ADMIN')")
    @RequiresReauth
    public ResponseEntity<Void> resetSales() {
        orderService.resetSales();
        audit.recordSuccess(AuditAction.SALES_STATS_RESET, "ORDER", null, null);
        return ResponseEntity.noContent().build();
    }

    // Hides from report page only
    @DeleteMapping("/reset-report")
    @PreAuthorize("hasRole('ADMIN')")
    @RequiresReauth
    public ResponseEntity<Void> resetReport() {
        orderService.resetReport();
        audit.recordSuccess(AuditAction.SALES_REPORT_RESET, "ORDER", null, null);
        return ResponseEntity.noContent().build();
    }
}