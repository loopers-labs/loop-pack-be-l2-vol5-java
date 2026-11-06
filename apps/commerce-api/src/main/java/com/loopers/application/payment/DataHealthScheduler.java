package com.loopers.application.payment;

import com.loopers.domain.order.OrderModel;
import com.loopers.domain.order.OrderService;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.payment.PaymentAttemptModel;
import com.loopers.domain.payment.PaymentAttemptService;
import com.loopers.domain.payment.PaymentAttemptStatus;
import com.loopers.domain.payment.PaymentDlqService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;
import java.util.List;

@Slf4j
@RequiredArgsConstructor
@Component
public class DataHealthScheduler {

    private final PaymentAttemptService paymentAttemptService;
    private final OrderService orderService;
    private final PaymentDlqService paymentDlqService;

    @Scheduled(fixedDelay = 300_000)
    public void checkDataHealth() {
        checkUnknownAttempts();
        checkOrphanPendingOrders();
    }

    private void checkUnknownAttempts() {
        ZonedDateTime threshold = ZonedDateTime.now().minusMinutes(30);
        List<PaymentAttemptModel> unknownAttempts = paymentAttemptService.findUnknownBefore(threshold);

        for (PaymentAttemptModel attempt : unknownAttempts) {
            try {
                paymentDlqService.enqueue(
                    attempt.getOrderId(),
                    "UNKNOWN 상태 30분 초과: providerOrderId=" + attempt.getProviderOrderId(),
                    PaymentAttemptStatus.UNKNOWN
                );
                log.warn("DLQ 등록 (UNKNOWN 초과): orderId={}", attempt.getOrderId());
            } catch (Exception e) {
                log.error("DLQ 등록 실패 (UNKNOWN): orderId={}", attempt.getOrderId(), e);
            }
        }
    }

    private void checkOrphanPendingOrders() {
        List<OrderModel> pendingOrders = orderService.findByStatus(OrderStatus.PAYMENT_PENDING);

        for (OrderModel order : pendingOrders) {
            try {
                boolean hasAttempt = paymentAttemptService.findByOrderId(order.getId()).isPresent();
                if (!hasAttempt) {
                    paymentDlqService.enqueue(
                        order.getId(),
                        "PAYMENT_PENDING 상태이나 결제 시도 레코드 없음",
                        null
                    );
                    log.warn("DLQ 등록 (고아 PAYMENT_PENDING): orderId={}", order.getId());
                }
            } catch (Exception e) {
                log.error("DLQ 등록 실패 (고아 PAYMENT_PENDING): orderId={}", order.getId(), e);
            }
        }
    }
}
