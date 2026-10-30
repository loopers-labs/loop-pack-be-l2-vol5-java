package com.loopers.infrastructure.order;

import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.order.PaymentMethod;
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

    // 결제 요청은 본인의 DRAFT 주문만 한 번 결제 대기로 바꾼다. 경쟁에서 진 요청은 0행이다.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        UPDATE OrderJpaEntity o
        SET o.status = :pendingStatus,
            o.paymentMethod = :paymentMethod,
            o.updatedAt = :requestedAt
        WHERE o.id = :orderId
          AND o.userId = :userId
          AND o.status = :draftStatus
        """)
    int requestPgPaymentIfDraft(
        @Param("orderId") Long orderId,
        @Param("userId") Long userId,
        @Param("draftStatus") OrderStatus draftStatus,
        @Param("pendingStatus") OrderStatus pendingStatus,
        @Param("paymentMethod") PaymentMethod paymentMethod,
        @Param("requestedAt") ZonedDateTime requestedAt
    );

    // 결제 결과 반영은 PAYMENT_PENDING 주문만 한 번 종결한다. 실패 종결은 결제액과 확정 시각을 남기지 않는다.
    // 같은 조건이라 이미 종결한 주문의 확정 시각은 이후 반영이 바꾸지 못한다.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        UPDATE OrderJpaEntity o
        SET o.status = :terminalStatus,
            o.paymentAmount = :paymentAmount,
            o.paymentResult = :paymentResult,
            o.confirmedAt = :confirmedAt,
            o.updatedAt = :terminatedAt
        WHERE o.id = :orderId
          AND o.status = :pendingStatus
        """)
    int terminatePgPaymentIfPending(
        @Param("orderId") Long orderId,
        @Param("pendingStatus") OrderStatus pendingStatus,
        @Param("terminalStatus") OrderStatus terminalStatus,
        @Param("paymentAmount") Long paymentAmount,
        @Param("paymentResult") PaymentResult paymentResult,
        @Param("confirmedAt") ZonedDateTime confirmedAt,
        @Param("terminatedAt") ZonedDateTime terminatedAt
    );
}
