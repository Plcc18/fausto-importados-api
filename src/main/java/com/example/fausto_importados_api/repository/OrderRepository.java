package com.example.fausto_importados_api.repository;

import com.example.fausto_importados_api.model.Order;
import com.example.fausto_importados_api.model.enums.OrderStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Repository
public interface OrderRepository extends JpaRepository<Order, UUID> {

    // All orders sorted by date (notifications panel — shows all statuses, not filtered by hidden)
    List<Order> findAllByOrderByCreatedAtDesc();

    // Orders by status, excluding those hidden from panel
    List<Order> findByStatusAndHiddenFromPanelFalseOrderByCreatedAtDesc(OrderStatus status);

    // Orders by status, excluding those hidden from report
    List<Order> findByStatusAndHiddenFromReportFalseOrderByCreatedAtDesc(OrderStatus status);

    // For notifications: all statuses, not filtered by hidden
    List<Order> findByStatusOrderByCreatedAtDesc(OrderStatus status);

    void deleteByStatusIn(List<OrderStatus> statuses);

    // Hide from panel (reset-sales)
    @Modifying
    @Transactional
    @Query("UPDATE Order o SET o.hiddenFromPanel = true WHERE o.status = 'COMPLETED' AND o.hiddenFromPanel = false")
    void hideCompletedFromPanel();

    // Hide from report (reset-report)
    @Modifying
    @Transactional
    @Query("UPDATE Order o SET o.hiddenFromReport = true WHERE o.status = 'COMPLETED' AND o.hiddenFromReport = false")
    void hideCompletedFromReport();
}