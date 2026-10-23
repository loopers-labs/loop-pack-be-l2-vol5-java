package com.loopers.order.infrastructure;

import com.loopers.order.domain.Order;
import com.loopers.order.domain.PaymentStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.List;
import java.util.Optional;

interface OrderJpaRepository extends JpaRepository<Order, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Order> findForConfirmById(Long id);
    Optional<Order> findByPayment_PaymentAttemptKey(String paymentAttemptKey);

    @EntityGraph(attributePaths = "payment")
    List<Order> findByPayment_StatusInAndIdGreaterThanOrderByIdAsc(
        List<PaymentStatus> statuses, Long afterOrderId, Pageable pageable);

    List<Order> findAllByBuyerId(Long buyerId, Pageable pageable);

    long countByBuyerId(Long buyerId);
}
