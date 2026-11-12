package com.loopers.application.payment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loopers.application.order.OrderConfirmedEvent;
import com.loopers.domain.order.OrderConfirmedOutbox;
import com.loopers.domain.order.OrderConfirmedOutboxRepository;
import com.loopers.domain.order.OrderModel;
import com.loopers.domain.order.OrderService;
import com.loopers.domain.payment.PaymentAttemptModel;
import com.loopers.domain.payment.PaymentAttemptService;
import com.loopers.domain.product.ProductService;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
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
    private final OrderConfirmedOutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

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
     * TX B: PG 결과를 적용. SUCCESS이면 예약 소비 + 주문 CONFIRMED + Outbox insert.
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

                OrderConfirmedEvent event = OrderConfirmedEvent.from(order);
                insertOutboxIfAbsent(event);
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

    private void insertOutboxIfAbsent(OrderConfirmedEvent event) {
        if (outboxRepository.findByEventId(event.eventId()).isPresent()) {
            log.info("Outbox 이미 존재 (중복 콜백 감지): eventId={}", event.eventId());
            return;
        }
        try {
            String payload = toOutboxPayloadJson(event);
            OrderConfirmedOutbox outbox = OrderConfirmedOutbox.create(
                event.eventId(),
                event.orderId(),
                String.valueOf(event.orderId()),
                payload
            );
            outboxRepository.save(outbox);
        } catch (JsonProcessingException e) {
            throw new CoreException(ErrorType.INTERNAL_ERROR, "Outbox payload 직렬화 실패: " + e.getMessage());
        }
    }

    private String toOutboxPayloadJson(OrderConfirmedEvent event) throws JsonProcessingException {
        ObjectNode node = objectMapper.valueToTree(event);
        node.put("eventType", "ORDER_CONFIRMED");
        node.put("schemaVersion", 1);
        return objectMapper.writeValueAsString(node);
    }
}
