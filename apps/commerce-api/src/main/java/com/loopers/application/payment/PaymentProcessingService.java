package com.loopers.application.payment;

import com.loopers.domain.order.OrderModel;
import com.loopers.domain.order.OrderService;
import com.loopers.domain.payment.PaymentAttemptModel;
import com.loopers.domain.payment.PaymentAttemptService;
import com.loopers.domain.product.ProductService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@RequiredArgsConstructor
@Service
public class PaymentProcessingService {

    private final OrderService orderService;
    private final ProductService productService;
    private final PaymentAttemptService paymentAttemptService;

    /**
     * TX A: 재고 예약 + 결제 시도 생성 + 주문 PAYMENT_PENDING 전이를 한 트랜잭션으로 처리.
     * providerOrderId 형식: LP-ORD-{10자리 zero-padded orderId}
     */
    @Transactional
    public PaymentAttemptModel initiatePayment(Long userId, Long orderId, String paymentAttemptKey) {
        OrderModel order = orderService.getMyOrderWithLock(userId, orderId);
        order.requireDraft();

        order.getItems().forEach(item ->
            productService.reserveStock(item.getProductId(), item.getQuantity())
        );

        String providerOrderId = "LP-ORD-" + String.format("%010d", orderId);
        PaymentAttemptModel attempt = paymentAttemptService.create(
            orderId, paymentAttemptKey, providerOrderId, order.getTotalAmount()
        );

        order.startPayment();
        orderService.save(order);

        return attempt;
    }

    /**
     * TX B: PG 결과를 적용. SUCCESS이면 예약 소비 + 주문 CONFIRMED.
     * FAILED/REJECTED이면 예약 반환 + 주문 PAYMENT_FAILED.
     * UNKNOWN이면 상태만 기록 (재고 보유 유지).
     */
    @Transactional
    public void applyPaymentResult(Long orderId, PgPaymentResult pgResult) {
        OrderModel order = orderService.getOrderByIdWithLock(orderId);
        PaymentAttemptModel attempt = paymentAttemptService.getByOrderId(orderId);

        if (attempt.isTerminal()) {
            log.warn("이미 종결된 결제 시도: orderId={}, status={}", orderId, attempt.getStatus());
            return;
        }

        switch (pgResult.status()) {
            case SUCCESS -> {
                order.getItems().forEach(item ->
                    productService.consumeReservation(item.getProductId(), item.getQuantity())
                );
                attempt.success(pgResult.transactionKey());
                order.confirmPayment();
            }
            case FAILED, REJECTED -> {
                order.getItems().forEach(item ->
                    productService.releaseReservation(item.getProductId(), item.getQuantity())
                );
                if (pgResult.isRejected()) {
                    attempt.rejectedNoEffect();
                } else {
                    attempt.failed();
                }
                order.failPayment();
            }
            case UNKNOWN, PENDING -> {
                attempt.unknown();
                log.warn("PG 응답 미확정 (UNKNOWN/PENDING): orderId={}", orderId);
            }
        }

        paymentAttemptService.save(attempt);
        orderService.save(order);
    }
}
