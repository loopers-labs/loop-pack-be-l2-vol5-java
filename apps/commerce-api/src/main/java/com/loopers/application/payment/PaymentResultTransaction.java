package com.loopers.application.payment;

import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderRepository;
import com.loopers.domain.payment.PaymentAttempt;
import com.loopers.domain.payment.PaymentAttemptRepository;
import com.loopers.domain.payment.PaymentAttemptStatus;
import com.loopers.domain.payment.PaymentConflictReason;
import com.loopers.domain.product.ProductRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.Optional;

// 결과 반영의 트랜잭션 경계다. PaymentResultService가 메서드마다 새 트랜잭션·영속성 컨텍스트로 호출한다.
@Slf4j
@RequiredArgsConstructor
@Component
public class PaymentResultTransaction {
    private final PaymentAttemptRepository paymentAttemptRepository;
    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;

    // TX B: 시도·주문·품목을 다시 읽고 도메인 전이 검사와 DB 조건부 갱신을 함께 적용한다.
    // 시도를 종결한 요청만 주문을 종결하고, 실패면 같은 트랜잭션에서 재고를 반환한다.
    @Transactional
    public PaymentReflectionOutcome apply(Long attemptId, PaymentObservation observation) {
        PaymentAttempt attempt = findAttempt(attemptId);
        try {
            Order order = orderRepository.findById(attempt.getOrderId())
                .orElseThrow(() -> integrityError("결제 시도의 주문이 없습니다."));
            return switch (observation) {
                case PaymentObservation.Transaction transaction -> applyTransaction(attempt, order, transaction);
                case PaymentObservation.Accepted accepted ->
                    applyObserved(attempt, order, accepted.transactionKey(), PaymentAttemptStatus.PENDING);
                case PaymentObservation.Unknown ignored -> applyUnknown(attempt);
                case PaymentObservation.RejectedBeforeCreate ignored -> applyRejection(attempt, order);
                case PaymentObservation.MultipleTransactions ignored ->
                    recordConflict(attempt, PaymentConflictReason.MULTIPLE_TRANSACTIONS);
            };
        } catch (RuntimeException exception) {
            log.error("결제 결과 반영을 되돌립니다. attemptId={}, orderId={}, cause={}",
                attempt.getId(), attempt.getOrderId(), exception.toString());
            throw exception;
        }
    }

    // 조건부 갱신이 0행이면 TX B가 끝난 뒤 새로 읽은 시도로 중복·늦은 관찰·충돌을 판정한다.
    // 충돌은 이 짧은 트랜잭션에서 기록하고 시도 상태·주문·재고는 바꾸지 않는다.
    @Transactional
    public PaymentReflectionOutcome explainUnchanged(Long attemptId, PaymentObservation observation) {
        PaymentAttempt attempt = findAttempt(attemptId);
        try {
            Optional<PaymentConflictReason> conflict = switch (observation) {
                case PaymentObservation.Transaction transaction ->
                    attempt.explainUnchangedTransaction(transaction.transactionKey(), transaction.status());
                case PaymentObservation.Accepted accepted ->
                    attempt.explainUnchangedTransaction(accepted.transactionKey(), PaymentAttemptStatus.PENDING);
                case PaymentObservation.Unknown ignored -> attempt.explainUnchangedUnknown();
                case PaymentObservation.RejectedBeforeCreate ignored -> attempt.explainUnchangedRejection();
                case PaymentObservation.MultipleTransactions ignored -> Optional.of(PaymentConflictReason.MULTIPLE_TRANSACTIONS);
            };
            return conflict.map(reason -> recordConflict(attempt, reason)).orElse(PaymentReflectionOutcome.UNCHANGED);
        } catch (RuntimeException exception) {
            log.error("결제 결과 반영을 판정하지 못했습니다. attemptId={}, orderId={}, cause={}",
                attempt.getId(), attempt.getOrderId(), exception.toString());
            throw exception;
        }
    }

    // 주기 조회의 확인 시각만 남긴다. 상태 변경 시각(updated_at)은 바꾸지 않는다.
    @Transactional
    public void markChecked(Long attemptId, ZonedDateTime checkedAt) {
        paymentAttemptRepository.touchLastChecked(attemptId, checkedAt);
    }

    @Transactional(readOnly = true)
    public PaymentStatusInfo read(Long attemptId) {
        PaymentAttempt attempt = findAttempt(attemptId);
        Order order = orderRepository.findById(attempt.getOrderId())
            .orElseThrow(() -> integrityError("결제 시도의 주문이 없습니다."));
        return PaymentStatusInfo.from(order, attempt);
    }

    private PaymentReflectionOutcome applyTransaction(PaymentAttempt attempt, Order order,
                                                      PaymentObservation.Transaction transaction) {
        Optional<PaymentConflictReason> mismatch = attempt.verifyTransaction(
            transaction.providerOrderId(), transaction.amount(), transaction.transactionKey());
        if (mismatch.isPresent()) {
            return recordConflict(attempt, mismatch.get());
        }
        return applyObserved(attempt, order, transaction.transactionKey(), transaction.status());
    }

    private PaymentReflectionOutcome applyObserved(PaymentAttempt attempt, Order order, String transactionKey,
                                                   PaymentAttemptStatus observedStatus) {
        if (!attempt.observe(transactionKey, observedStatus)) {
            return PaymentReflectionOutcome.UNCHANGED;
        }
        ZonedDateTime now = ZonedDateTime.now();
        if (observedStatus == PaymentAttemptStatus.PENDING) {
            int updated = paymentAttemptRepository.markPending(attempt.getId(), transactionKey, now);
            return updated == 1 ? PaymentReflectionOutcome.APPLIED : PaymentReflectionOutcome.UNCHANGED;
        }
        if (paymentAttemptRepository.terminate(attempt.getId(), observedStatus, transactionKey, now) != 1) {
            return PaymentReflectionOutcome.UNCHANGED;
        }
        if (observedStatus == PaymentAttemptStatus.SUCCEEDED) {
            completeOrder(order, now);
        } else {
            failOrderAndReturnStock(order, now);
        }
        return PaymentReflectionOutcome.APPLIED;
    }

    private PaymentReflectionOutcome applyUnknown(PaymentAttempt attempt) {
        if (!attempt.markUnknown() || paymentAttemptRepository.markUnknown(attempt.getId(), ZonedDateTime.now()) != 1) {
            return PaymentReflectionOutcome.UNCHANGED;
        }
        return PaymentReflectionOutcome.APPLIED;
    }

    private PaymentReflectionOutcome applyRejection(PaymentAttempt attempt, Order order) {
        ZonedDateTime now = ZonedDateTime.now();
        if (!attempt.rejectBeforeCreate() || paymentAttemptRepository.rejectBeforeCreate(attempt.getId(), now) != 1) {
            return PaymentReflectionOutcome.UNCHANGED;
        }
        failOrderAndReturnStock(order, now);
        return PaymentReflectionOutcome.APPLIED;
    }

    private void completeOrder(Order order, ZonedDateTime now) {
        order.completePgPayment();
        if (orderRepository.completePgPaymentIfPending(order.getId(), order.getPaymentAmount(), now) != 1) {
            throw integrityError("결제 대기 주문을 확정하지 못했습니다.");
        }
    }

    // 결제 요청 이후 고정된 품목 스냅샷의 수량만큼 Product ID 오름차순으로 반환한다.
    private void failOrderAndReturnStock(Order order, ZonedDateTime now) {
        order.failPgPayment();
        if (orderRepository.failPgPaymentIfPending(order.getId(), now) != 1) {
            throw integrityError("결제 대기 주문을 실패로 종결하지 못했습니다.");
        }
        order.getItems().stream()
            .sorted(Comparator.comparing(OrderItem::getProductId))
            .forEach(item -> {
                if (productRepository.increaseStock(item.getProductId(), item.getQuantity(), now) != 1) {
                    throw integrityError("재고를 반환할 상품이 없습니다.");
                }
            });
    }

    // 처음 기록한 사유만 남는다. 이미 기록돼 있어도 이 관찰은 충돌로 처리한다.
    private PaymentReflectionOutcome recordConflict(PaymentAttempt attempt, PaymentConflictReason reason) {
        ZonedDateTime now = ZonedDateTime.now();
        if (attempt.recordConflict(reason, now) && paymentAttemptRepository.recordConflict(attempt.getId(), reason, now) == 1) {
            log.warn("결제 관찰 충돌을 기록했습니다. attemptId={}, orderId={}, reason={}",
                attempt.getId(), attempt.getOrderId(), reason);
        }
        return PaymentReflectionOutcome.CONFLICT;
    }

    private PaymentAttempt findAttempt(Long attemptId) {
        return paymentAttemptRepository.findById(attemptId)
            .orElseThrow(() -> integrityError("결제 시도가 없습니다."));
    }

    private static CoreException integrityError(String message) {
        return new CoreException(ErrorType.INTERNAL_ERROR, message);
    }
}
