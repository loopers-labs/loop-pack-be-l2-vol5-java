package com.loopers.application.order;

import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.order.PaymentMethod;
import com.loopers.domain.payment.PaymentAttempt;
import com.loopers.domain.payment.PaymentAttemptStatus;

import java.time.ZonedDateTime;
import java.util.List;

// 고객·관리자 조회가 공유하는 Info — 필드 노출 범위는 interfaces 계층의 응답 DTO가 결정한다
// (docs/week2/design.md 1번 섹션, 7번 섹션 참고). Order는 고객·관리자 응답 필드 차이가 계약표에
// 없어서 하나로 둔다.
public record OrderInfo(
    Long id,
    Long userId,
    OrderStatus status,
    PaymentMethod paymentMethod,
    Payment payment,
    List<Item> items,
    Long paidAmount,
    ZonedDateTime confirmedAt,
    ZonedDateTime createdAt
) {
    /** 결제 시도가 없는 주문(DRAFT, 이전 POINT 확정 주문) — payment가 비어 있다(docs/week5/design.md 6번). */
    public static OrderInfo from(Order order) {
        return from(order, null);
    }

    public static OrderInfo from(Order order, PaymentAttempt attempt) {
        return new OrderInfo(
            order.getId(),
            order.getUserId(),
            order.getStatus(),
            order.getPaymentMethod(),
            attempt == null ? null : Payment.from(attempt),
            order.getItems().stream()
                .map(item -> new Item(item.getProductId(), item.getQuantity(), item.getUnitPrice()))
                .toList(),
            order.getPaidAmount(),
            order.getConfirmedAt(),
            order.getCreatedAt()
        );
    }

    public record Item(Long productId, int quantity, Long unitPrice) {}

    /**
     * 결제 시도 상태는 줄이지 않고 그대로 싣는다 — 처리중과 UNKNOWN을 구분해야 앱이 다음 행동을 고른다(D-023).
     * PG 식별자·확인 일정·관리자 처리 기록은 관리자 응답만 내보낸다(D-024). 고객 응답 DTO는 앞의 세 필드만 쓴다.
     */
    public record Payment(
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
        static Payment from(PaymentAttempt attempt) {
            return new Payment(
                attempt.getAttemptKey(),
                attempt.getStatus(),
                attempt.getAmount(),
                attempt.getProviderOrderId(),
                attempt.getTransactionKey(),
                attempt.getSendStartedAt(),
                attempt.getEmptyLookupCount(),
                attempt.getReviewRequestedAt(),
                attempt.getResolvedBy(),
                attempt.getResolvedAt(),
                attempt.getWatchUntil()
            );
        }
    }
}
