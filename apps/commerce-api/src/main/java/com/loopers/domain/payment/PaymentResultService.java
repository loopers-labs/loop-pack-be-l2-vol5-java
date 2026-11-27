package com.loopers.domain.payment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderConfirmedEvent;
import com.loopers.domain.order.OrderConfirmedOutbox;
import com.loopers.domain.order.OrderConfirmedOutboxRepository;
import com.loopers.domain.order.OrderRepository;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.product.StockReservation;
import com.loopers.domain.product.StockReservationRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;

/**
 * 로컬 TX B. create 응답·callback·reconcile 세 입구가 공유하며, 검증된 PG 결과를 시도·주문·예약에 한 번만 반영한다.
 * ⑥ 결제 시도 문지기(미종결일 때만 종결) → ⑦ 주문 상태(WHERE PAYMENT_PENDING) → ⑧ 예약(WHERE RESERVED, 실패면 재고 1회 복원)
 * 세 단계는 반드시 하나의 트랜잭션 안에 있어야 한다 (설계 5-2).
 * 7주차: ⑦ 이 주문을 처음 CONFIRMED 로 만들었으면 같은 TX 에 OrderConfirmed Outbox 를 PENDING 으로 insert 한다 (7주차 설계 D10·D16).
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class PaymentResultService {

    private final PaymentAttemptRepository paymentAttemptRepository;
    private final OrderRepository orderRepository;
    private final StockReservationRepository stockReservationRepository;
    private final ProductRepository productRepository;
    private final OrderConfirmedOutboxRepository orderConfirmedOutboxRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /**
     * ⑥⑦⑧ 을 한 TX 로 실행한다.
     *
     * @return ⑥ 이 1행이면 applied=true. 0행(이미 종결)이면 아무 효과 없이 applied=false.
     *         ⑦ 이 주문을 처음 CONFIRMED 로 만든 경우에만 firstConfirmation(발행 payload)이 채워진다 (6주차 설계 D3·D4).
     */
    @Transactional
    public PaymentApplyResult applyFinal(Long attemptId, PaymentFinalResult result) {
        PaymentAttempt attempt = paymentAttemptRepository.findById(attemptId)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "[id = " + attemptId + "] 결제 시도를 찾을 수 없습니다."));

        int settled = paymentAttemptRepository.settleIfOpen(
            attemptId, result.status(), result.failureReason(), result.transactionKey());
        if (settled == 0) {
            // 조건부 UPDATE 가 0 행이라는 사실만 남긴다. 이것은 "적용하지 않았다"는 뜻이고, 그 자체로 중복 적용을 뜻하지는 않는다.
            // storedStatus(이 TX 시작 시점에 읽은 저장 상태)와 targetStatus 가 같을 때만 "같은 결과로 이미 종결"로 읽을 수 있다.
            // 다르면 다른 결과가 먼저 종결된 것이므로 중복이 아니다. 판단은 읽는 쪽이 두 값을 보고 한다.
            log.info("payment.apply phase=attempt attemptId={} orderId={} storedStatus={} targetStatus={} settled=0 result=NOT_APPLIED",
                attemptId, attempt.getOrderId(), attempt.getStatus(), result.status());
            return PaymentApplyResult.notApplied();
        }

        // 확정 시각은 밀리초로 잘라 한 번만 만든다. 저장(confirmedAt·updatedAt)과 발행 payload 가 같은 값을 쓴다 (6주차 설계 D2).
        ZonedDateTime at = ZonedDateTime.now(clock).truncatedTo(ChronoUnit.MILLIS);
        OrderConfirmedEvent firstConfirmation = null;
        if (result.isSuccess()) {
            int confirmedRows = orderRepository.finishIfPaymentPending(
                attempt.getOrderId(), Order.OrderStatus.CONFIRMED, attempt.getAmount(), at);
            if (confirmedRows == 1) {
                // 벌크 UPDATE 는 영속성 컨텍스트를 거치지 않으므로, 같은 TX 에서 먼저 읽힌 Order 엔티티는 confirmedAt 이 null 일 수 있다.
                // 그래서 confirmedAt 은 엔티티가 아니라 위의 at 을 쓰고, 품목만 DB 에서 읽는다. payload 는 TX 안에서 완성한다 (D4).
                Order order = orderRepository.findByIdWithItems(attempt.getOrderId())
                    .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "[id = " + attempt.getOrderId() + "] 주문을 찾을 수 없습니다."));
                firstConfirmation = OrderConfirmedEvent.of(attempt.getOrderId(), at, order.getItems());
                // 같은 TX 에 Outbox PENDING insert. payload 는 여기서 한 번만 직렬화해 저장하고 relay 는 그대로 보낸다 (D12).
                // 직렬화·저장 실패는 런타임 예외로 올려 ⑥⑦⑧ 과 함께 rollback 된다 (D16). 기존 주문·시도·예약은 TX 직전 상태로 남는다.
                orderConfirmedOutboxRepository.save(
                    OrderConfirmedOutbox.pending(firstConfirmation, serialize(firstConfirmation), at));
            }
        } else {
            orderRepository.finishIfPaymentPending(attempt.getOrderId(), Order.OrderStatus.PAYMENT_FAILED, 0L, at);
        }

        // TX 안이므로 아직 커밋되지 않았다. 성공을 단정하는 문구를 쓰지 않고 phase=attempt 로만 남긴다.
        // storedStatus 는 이 TX 시작 시점에 읽은 저장 상태, targetStatus 는 적용하려는 목표 상태다. 실제 저장 결과는 commit 뒤에야 확정된다.
        log.info("payment.apply phase=attempt attemptId={} orderId={} storedStatus={} targetStatus={} settled=1 firstConfirmation={}",
            attemptId, attempt.getOrderId(), attempt.getStatus(), result.status(), firstConfirmation != null);
        registerCompletionLog(attemptId, attempt.getOrderId(), result.status(), at, firstConfirmation);

        for (StockReservation reservation : stockReservationRepository.findAllByOrderId(attempt.getOrderId())) {
            if (result.isSuccess()) {
                stockReservationRepository.consumeIfReserved(reservation.getId());
            } else if (stockReservationRepository.releaseIfReserved(reservation.getId()) == 1) {
                productRepository.restoreStock(reservation.getProductId(), reservation.getQuantity());
            }
        }
        return PaymentApplyResult.applied(firstConfirmation);
    }

    /**
     * Outbox payload 직렬화. 체크 예외를 그대로 두면 @Transactional 이 rollback 하지 않으므로 런타임 예외로 바꾼다 (D16).
     */
    private String serialize(OrderConfirmedEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            log.error("payment.apply phase=attempt orderId={} reason=payload-serialize", event.orderId());
            throw new CoreException(ErrorType.INTERNAL_ERROR,
                "[eventId = " + event.eventId() + "] OrderConfirmed Outbox payload 직렬화에 실패해 확정을 취소합니다.");
        }
    }

    /**
     * 적용 시도와 실제 반영을 로그에서 구분하기 위한 TX 완료 콜백 등록 (10주차 설계 3-2).
     * phase=attempt 는 TX 안의 사실이라 뒤에서 rollback 될 수 있다. 그래서 실제 결과는 완료 상태를 확인한 뒤 남긴다.
     * afterCompletion 이 불렸다는 사실만으로 rollback 이라고 쓰지 않고 status 를 보고 분기한다.
     * 로그 전용이며 업무 로직·TX 경계·반환값을 바꾸지 않는다.
     */
    private void registerCompletionLog(
        Long attemptId, Long orderId, PaymentAttempt.Status targetStatus, ZonedDateTime at, OrderConfirmedEvent firstConfirmation
    ) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        String eventId = firstConfirmation == null ? null : firstConfirmation.eventId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int completionStatus) {
                switch (completionStatus) {
                    // committed 는 "내부 트랜잭션이 커밋됐다"는 뜻이다. 결제 성공을 뜻하지 않는다.
                    // appliedStatus 가 FAILED 인 커밋도 정상이며, 그 경우 주문은 PAYMENT_FAILED 가 되고 예약은 반환된다.
                    case STATUS_COMMITTED -> log.info(
                        "payment.apply phase=committed attemptId={} orderId={} appliedStatus={} confirmedAt={} outboxEventId={}",
                        attemptId, orderId, targetStatus, firstConfirmation == null ? null : at, eventId);
                    case STATUS_ROLLED_BACK -> log.warn(
                        "payment.apply phase=rolled-back attemptId={} orderId={} targetStatus={}", attemptId, orderId, targetStatus);
                    default -> log.warn(
                        "payment.apply phase=completion-unknown attemptId={} orderId={} targetStatus={}", attemptId, orderId, targetStatus);
                }
            }
        });
    }

    /**
     * 생성 수락(PENDING) 기록. ⑥ 만 실행하고 ⑦⑧ 은 실행하지 않는다.
     *
     * @return 갱신된 행 수. 0 이면 이미 PENDING 이거나 종결된 시도다.
     */
    @Transactional
    public int recordPending(Long attemptId, String transactionKey) {
        return paymentAttemptRepository.markPendingIfNotSettled(attemptId, transactionKey);
    }

    /**
     * 미확인(UNKNOWN) 기록. REQUESTED 일 때만 바꾸며 ⑦⑧ 은 실행하지 않는다.
     *
     * @return 갱신된 행 수
     */
    @Transactional
    public int recordUnknown(Long attemptId) {
        return paymentAttemptRepository.markUnknownIfRequested(attemptId);
    }

    /**
     * 충돌 기록 (설계 7-2). 상태·주문·예약은 건드리지 않는다.
     */
    @Transactional
    public void recordConflict(Long attemptId, PaymentAttempt.ConflictReason reason) {
        paymentAttemptRepository.recordConflict(attemptId, reason, ZonedDateTime.now(clock));
    }

    /**
     * 스케줄러 조회 1회 기록 (설계 5-3 결정 ⑫). 조회 횟수 +1, 다음 조회 시각, 한도 도달 시 수동 확인 표시.
     */
    @Transactional
    public void recordLookup(Long attemptId, ZonedDateTime nextLookupAt, boolean manualCheckRequired) {
        paymentAttemptRepository.recordLookup(attemptId, nextLookupAt, manualCheckRequired);
    }
}