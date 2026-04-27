package com.example.fausto_importados_api.controller;

import com.example.fausto_importados_api.dto.CreateOrderDTO;
import com.example.fausto_importados_api.dto.SalesStatsDTO;
import com.example.fausto_importados_api.model.Order;
import com.example.fausto_importados_api.model.enums.OrderStatus;
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

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    public ResponseEntity<Order> createOrder(@RequestBody CreateOrderDTO dto) {
        return ResponseEntity.ok(orderService.createOrder(dto));
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<Order>> getAllOrders() {
        return ResponseEntity.ok(orderService.findAll());
    }

    @GetMapping("/filter")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<Order>> getByStatus(@RequestParam OrderStatus status) {
        return ResponseEntity.ok(orderService.findByStatus(status));
    }

    // Report page — only orders not hidden from report
    @GetMapping("/report")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<Order>> getForReport() {
        return ResponseEntity.ok(orderService.findCompletedForReport());
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
            return ResponseEntity.ok(orderService.complete(id));
        } catch (BusinessException e) {
            // Returns 409 with the stock error message so the frontend can show it
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Order> cancelOrder(@PathVariable UUID id) {
        return ResponseEntity.ok(orderService.cancel(id));
    }

    @DeleteMapping("/clear-history")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> clearHistory() {
        orderService.clearHistory();
        return ResponseEntity.noContent().build();
    }

    // Hides from admin panel stats only
    @DeleteMapping("/reset-sales")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> resetSales() {
        orderService.resetSales();
        return ResponseEntity.noContent().build();
    }

    // Hides from report page only
    @DeleteMapping("/reset-report")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> resetReport() {
        orderService.resetReport();
        return ResponseEntity.noContent().build();
    }
}