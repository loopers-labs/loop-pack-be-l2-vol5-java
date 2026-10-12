package com.loopers.infrastructure.order;

import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.order.PaymentResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

public interface OrderJpaRepository extends JpaRepository<OrderJpaEntity, Long> {

    @EntityGraph(attributePaths = "items")
    @Override
    Optional<OrderJpaEntity> findById(Long orderId);

    @EntityGraph(attributePaths = "items")
    List<OrderJpaEntity> findAllByUserId(Long userId);

    @EntityGraph(attributePaths = "items")
    @Override
    List<OrderJpaEntity> findAll();

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        UPDATE OrderJpaEntity o
        SET o.status = :confirmedStatus,
            o.paymentAmount = :paymentAmount,
            o.paymentResult = :paymentResult,
            o.updatedAt = :confirmedAt
        WHERE o.id = :orderId
          AND o.userId = :userId
          AND o.status = :draftStatus
        """)
    int confirmIfDraft(
        @Param("orderId") Long orderId,
        @Param("userId") Long userId,
        @Param("paymentAmount") long paymentAmount,
        @Param("draftStatus") OrderStatus draftStatus,
        @Param("confirmedStatus") OrderStatus confirmedStatus,
        @Param("paymentResult") PaymentResult paymentResult,
        @Param("confirmedAt") ZonedDateTime confirmedAt
    );
}
