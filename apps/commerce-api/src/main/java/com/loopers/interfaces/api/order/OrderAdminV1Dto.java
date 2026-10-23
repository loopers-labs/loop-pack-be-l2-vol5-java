package com.loopers.interfaces.api.order;

import com.loopers.application.order.OrderAdminInfo;
import com.loopers.application.order.OrderInfo;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.order.PaymentMethod;
import com.loopers.domain.payment.PaymentAttemptStatus;
import com.loopers.domain.payment.PaymentConflictReason;
import com.loopers.domain.payment.PaymentObservation;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * 관리자 주문 응답 — 고객 응답에 PG 식별자·확인 일정·관리자 처리 기록을 더하고, 상세에는 충돌 목록을 붙인다(docs/week5/design.md D-024).
 */
public class OrderAdminV1Dto {

    public record PaymentResponse(
        String attemptKey,
        PaymentAttemptStatus status,
        long amount,
        String providerOrderId,
        String transactionKey,
        ZonedDateTime sendStartedAt,
        int emptyLookupCount,
        ZonedDateTime reviewRequestedAt,
        String resolvedBy,
        ZonedDateTime resolvedAt,
        ZonedDateTime watchUntil
    ) {
        static PaymentResponse from(OrderInfo.Payment payment) {
            if (payment == null) {
                return null;
            }
            return new PaymentResponse(payment.attemptKey(), payment.status(), payment.amount(), payment.providerOrderId(),
                payment.transactionKey(), payment.sendStartedAt(), payment.emptyLookupCount(), payment.reviewRequestedAt(),
                payment.resolvedBy(), payment.resolvedAt(), payment.watchUntil());
        }
    }

    public record ConflictResponse(
        PaymentConflictReason reason,
        PaymentAttemptStatus recordedStatus,
        PaymentObservation.Status observedStatus,
        String observedTransactionKey,
        Long observedAmount,
        ZonedDateTime firstDetectedAt,
        ZonedDateTime lastDetectedAt,
        ZonedDateTime resolvedAt
    ) {
        static ConflictResponse from(OrderAdminInfo.Conflict conflict) {
            return new ConflictResponse(conflict.reason(), conflict.recordedStatus(), conflict.observedStatus(),
                conflict.observedTransactionKey(), conflict.observedAmount(), conflict.firstDetectedAt(),
                conflict.lastDetectedAt(), conflict.resolvedAt());
        }
    }

    public record OrderResponse(
        Long id,
        Long userId,
        OrderStatus status,
        PaymentMethod paymentMethod,
        PaymentResponse payment,
        List<OrderV1Dto.ItemResponse> items,
        Long paidAmount,
        ZonedDateTime confirmedAt,
        ZonedDateTime createdAt
    ) {
        public static OrderResponse from(OrderInfo info) {
            return new OrderResponse(
                info.id(),
                info.userId(),
                info.status(),
                info.paymentMethod(),
                PaymentResponse.from(info.payment()),
                info.items().stream()
                    .map(item -> new OrderV1Dto.ItemResponse(item.productId(), item.quantity(), item.unitPrice()))
                    .toList(),
                info.paidAmount(),
                info.confirmedAt(),
                info.createdAt()
            );
        }
    }

    /** 상세는 목록 항목과 같은 필드에 충돌 목록을 더한다 — 기존 응답 필드를 감싸지 않고 그대로 둔다. */
    public record OrderDetailResponse(
        Long id,
        Long userId,
        OrderStatus status,
        PaymentMethod paymentMethod,
        PaymentResponse payment,
        List<OrderV1Dto.ItemResponse> items,
        Long paidAmount,
        ZonedDateTime confirmedAt,
        ZonedDateTime createdAt,
        List<ConflictResponse> conflicts
    ) {
        public static OrderDetailResponse from(OrderAdminInfo info) {
            OrderResponse order = OrderResponse.from(info.order());
            return new OrderDetailResponse(
                order.id(),
                order.userId(),
                order.status(),
                order.paymentMethod(),
                order.payment(),
                order.items(),
                order.paidAmount(),
                order.confirmedAt(),
                order.createdAt(),
                info.conflicts().stream().map(ConflictResponse::from).toList()
            );
        }

        /** 결제가 아직 끝나지 않았다 — 관리자 단건 확인 응답은 202, 끝났으면 200(고객 reconcile과 같은 규칙). */
        boolean paymentInProgress() {
            return payment != null && !payment.status().isTerminal();
        }
    }

    public record OrderListResponse(List<OrderResponse> orders) {
        public static OrderListResponse from(List<OrderInfo> infos) {
            return new OrderListResponse(infos.stream().map(OrderResponse::from).toList());
        }
    }
}
