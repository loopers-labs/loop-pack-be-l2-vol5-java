package com.loopers.domain.payment;

import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderConfirmedEvent;
import com.loopers.domain.order.OrderRepository;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.product.StockReservation;
import com.loopers.domain.product.StockReservationRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;

/**
 * 로컬 TX B. create 응답·callback·reconcile 세 입구가 공유하며, 검증된 PG 결과를 시도·주문·예약에 한 번만 반영한다.
 * ⑥ 결제 시도 문지기(미종결일 때만 종결) → ⑦ 주문 상태(WHERE PAYMENT_PENDING) → ⑧ 예약(WHERE RESERVED, 실패면 재고 1회 복원)
 * 세 단계는 반드시 하나의 트랜잭션 안에 있어야 한다 (설계 5-2).
 */
@RequiredArgsConstructor
@Component
public class PaymentResultService {

    private final PaymentAttemptRepository paymentAttemptRepository;
    private final OrderRepository orderRepository;
    private final StockReservationRepository stockReservationRepository;
    private final ProductRepository productRepository;
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
            }
        } else {
            orderRepository.finishIfPaymentPending(attempt.getOrderId(), Order.OrderStatus.PAYMENT_FAILED, 0L, at);
        }

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