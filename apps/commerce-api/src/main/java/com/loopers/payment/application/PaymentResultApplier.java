package com.loopers.payment.application;

import com.loopers.common.domain.Money;
import com.loopers.order.application.port.out.OrderPort;
import com.loopers.order.domain.OrderModel;
import com.loopers.payment.application.port.out.PaymentGatewayPort;
import com.loopers.payment.application.port.out.PaymentOutboxPort;
import com.loopers.payment.application.port.out.PaymentPort;
import com.loopers.payment.application.port.out.PgLookupUnavailableException;
import com.loopers.payment.application.port.out.PgTransaction;
import com.loopers.payment.domain.ExternalPaymentStatus;
import com.loopers.payment.domain.Payment;
import com.loopers.payment.domain.PaymentApplyOutcome;
import com.loopers.payment.domain.PaymentObservation;
import com.loopers.payment.domain.PaymentStatus;
import com.loopers.product.application.port.in.StockReservationUseCase;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * 결과 적용 경계 (plan.md 4장, 4-1, ADR-W5-04). callback·reconcile·생성 응답·발송 실패·정리·만료가 모두 여기로 와서 결제와 주문·재고를 함께 바꾼다.
 * <ul>
 *   <li>PG 조회는 트랜잭션·잠금 밖에서 한다.</li>
 *   <li>반영은 매번 새 트랜잭션(REQUIRES_NEW)이다 — AFTER_COMMIT 안(풀 거절)에서 불려도 이미 끝난 TX에 섞이지 않는다.</li>
 *   <li>잠금 순서: 발송 장부 → payment → 주문 → 선점 → 상품. 발송 claim(장부 → payment)과 같은 방향이다.</li>
 *   <li>결제가 이번 호출로 종결됐을 때(APPLIED)만 주문·선점을 바꾸고, 주문·선점도 자기 상태로 한 번 더 막는다.</li>
 * </ul>
 */
@Slf4j
@Component
public class PaymentResultApplier {

    private static final String NOT_FOUND = "NOT_FOUND";
    private static final String UNAVAILABLE = "UNAVAILABLE";

    private final PaymentPort paymentPort;
    private final PaymentOutboxPort paymentOutboxPort;
    private final PaymentGatewayPort paymentGatewayPort;
    private final OrderPort orderPort;
    private final StockReservationUseCase stockReservationUseCase;
    private final TransactionTemplate newTransaction;

    public PaymentResultApplier(
        PaymentPort paymentPort,
        PaymentOutboxPort paymentOutboxPort,
        PaymentGatewayPort paymentGatewayPort,
        OrderPort orderPort,
        StockReservationUseCase stockReservationUseCase,
        PlatformTransactionManager transactionManager
    ) {
        this.paymentPort = paymentPort;
        this.paymentOutboxPort = paymentOutboxPort;
        this.paymentGatewayPort = paymentGatewayPort;
        this.orderPort = orderPort;
        this.stockReservationUseCase = stockReservationUseCase;
        this.newTransaction = new TransactionTemplate(transactionManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * 결과를 모르는 결제(PENDING·UNKNOWN)를 PG 권위 조회로 확인한다. 생성하지 않고, 0건을 실패로 확정하지 않는다 (plan.md 5-1).
     * 거래 키가 있으면 그 거래 한 건, 없으면 PG 주문 식별자로 목록을 찾는다. 발송 전·중(REQUESTED·SENDING)이면 건너뛴다.
     * 종결된 결제도 조회한다 — 늦게 온 callback이 종결과 반대 결과를 가리키면 CONFLICT로 이상을 남긴다 (R-4).
     */
    public PaymentApplyOutcome reconcile(Long paymentId) {
        Payment payment = paymentPort.findById(paymentId).orElseThrow();
        if (payment.getStatus() == PaymentStatus.REQUESTED || payment.getStatus() == PaymentStatus.SENDING) {
            return PaymentApplyOutcome.NOT_FINAL;
        }
        try {
            if (payment.getTransactionKey() != null) {
                Optional<PgTransaction> found = paymentGatewayPort.findByTransactionKey(payment.getTransactionKey());
                return found.map(transaction -> apply(paymentId, transaction))
                    .orElseGet(() -> recordCheck(paymentId, NOT_FOUND));
            }
            List<PgTransaction> found = paymentGatewayPort.findByProviderOrderId(payment.getProviderOrderId());
            if (found.isEmpty()) {
                return recordCheck(paymentId, NOT_FOUND);
            }
            if (found.size() > 1) {
                log.warn("PG 거래가 여러 건입니다 — 상태를 바꾸지 않습니다. paymentId={}, count={}", paymentId, found.size());
                return recordCheck(paymentId, "MULTIPLE:" + found.size());
            }
            return apply(paymentId, found.get(0));
        } catch (PgLookupUnavailableException e) {
            log.info("PG 조회 불가 — 다음 조회로 미룹니다. paymentId={}, reason={}", paymentId, e.getMessage());
            return recordCheck(paymentId, UNAVAILABLE);
        }
    }

    /**
     * PG가 알려 준 거래 한 건을 반영한다 (조회 결과). 식별·금액이 다르거나 종결과 모순이면 상태를 두고 경고한다.
     */
    public PaymentApplyOutcome apply(Long paymentId, PgTransaction observed) {
        return inLockedPayment(paymentId, false, payment -> observe(payment, observed));
    }

    /**
     * 생성 응답 (TX B). 수락(PENDING)은 거래 키를 기록하고, 최종 상태면 조회 결과처럼 반영한다. 발송 장부를 끝낸다.
     */
    public PaymentApplyOutcome recordCreated(Long paymentId, PgTransaction created) {
        return inLockedPayment(paymentId, true, payment -> switch (created.status()) {
            case PENDING -> payment.recordAccepted(created.transactionKey(), ZonedDateTime.now());
            case SUCCESS, FAILED -> observe(payment, created);
        });
    }

    /**
     * PG가 거래를 만들지 않고 거절했다 (효과 0건) → 실패로 확정, 재고 반환 (TX B).
     */
    public PaymentApplyOutcome recordRejected(Long paymentId, String reason) {
        return inLockedPayment(paymentId, true, payment -> payment.recordRejected(reason, ZonedDateTime.now()));
    }

    /**
     * 보냈을 수 있으나 결과를 모른다 → UNKNOWN, 조회로만 종결 (TX B). 정리 스케줄러가 멈춘 SENDING에도 쓴다.
     */
    public PaymentApplyOutcome recordUnknown(Long paymentId) {
        return inLockedPayment(paymentId, true, payment -> payment.recordUnknown(ZonedDateTime.now()));
    }

    /**
     * 발송 권한을 얻었지만 호출 전에 막혔다 (회로 OPEN·연결 실패) → FAILED(NOT_SENT), 재고 반환 (ADR-W5-03).
     */
    public PaymentApplyOutcome closeNotSentAfterClaim(Long paymentId) {
        return inLockedPayment(paymentId, true, payment -> payment.closeNotSent(ZonedDateTime.now()));
    }

    /**
     * 아무도 발송 권한을 얻지 않은 결제(장부 PENDING)를 보내지 않고 닫는다 — 풀 거절·정리 스케줄러 (ADR-W5-03).
     * 장부를 조건부로 닫는 데 성공한 실행만 결제를 닫는다. 동시에 claim한 발송이 이겼으면 아무것도 하지 않는다.
     */
    public PaymentApplyOutcome closeUnclaimed(Long paymentId) {
        return newTransaction.execute(status -> {
            if (!paymentOutboxPort.close(paymentId, ZonedDateTime.now())) {
                return PaymentApplyOutcome.ALREADY_APPLIED;
            }
            Payment payment = paymentPort.findByIdForUpdate(paymentId).orElseThrow();
            return afterPaymentChanged(payment, payment.closeNotSent(ZonedDateTime.now()));
        });
    }

    /**
     * 보낸 뒤 30분 넘게 결과를 모르는 결제(PENDING·UNKNOWN)를 FAILED(EXPIRED)로 닫고 주문 PAYMENT_FAILED·재고 반환 (P-14).
     * PG 마지막 조회는 호출자가 잠금 밖에서 먼저 한다. 그 사이 종결됐으면 ALREADY_APPLIED로 아무것도 바꾸지 않는다.
     */
    public PaymentApplyOutcome expire(Long paymentId) {
        return inLockedPayment(paymentId, false, payment -> payment.expireUnresolved(ZonedDateTime.now()));
    }

    private PaymentApplyOutcome recordCheck(Long paymentId, String result) {
        return inLockedPayment(paymentId, false, payment -> {
            if (payment.isFinal()) {
                return PaymentApplyOutcome.ALREADY_APPLIED;
            }
            payment.recordCheck(result, ZonedDateTime.now());
            return PaymentApplyOutcome.NOT_FINAL;
        });
    }

    private PaymentApplyOutcome observe(Payment payment, PgTransaction observed) {
        PaymentObservation observation = new PaymentObservation(
            observed.transactionKey(), observed.providerOrderId(), Money.of(observed.amount()), observed.status(), observed.reason());
        PaymentApplyOutcome outcome = payment.applyObservation(observation, ZonedDateTime.now());
        if (outcome == PaymentApplyOutcome.CONFLICT && isSuccessAfterExpiry(payment, observed)) {
            log.error("만료 뒤 성공 — 환불 필요. paymentId={}, providerOrderId={}, transactionKey={}",
                payment.getId(), payment.getProviderOrderId(), observed.transactionKey());
        } else if (outcome == PaymentApplyOutcome.CONFLICT || outcome == PaymentApplyOutcome.MISMATCH) {
            log.warn("PG 결과가 저장된 결제와 맞지 않아 반영하지 않습니다. paymentId={}, outcome={}, check={}",
                payment.getId(), outcome, payment.getLastCheckResult());
        }
        return outcome;
    }

    /**
     * P-15: 만료로 닫은 뒤 PG가 실제로는 승인했다 — FAILED는 그대로 두고 사람이 환불해야 한다.
     */
    private static boolean isSuccessAfterExpiry(Payment payment, PgTransaction observed) {
        return Payment.EXPIRED.equals(payment.getFailureReason()) && observed.status() == ExternalPaymentStatus.SUCCESS;
    }

    /**
     * 새 트랜잭션에서 (필요하면 발송 장부를 먼저 끝내고) 결제를 잠가 바꾼 뒤, 이번 호출이 종결로 옮겼으면 주문·선점까지 같은 트랜잭션으로 반영한다.
     * 장부 갱신은 영속성 컨텍스트를 비우므로 결제를 읽기 전에 한다.
     */
    private PaymentApplyOutcome inLockedPayment(Long paymentId, boolean finishOutbox, Function<Payment, PaymentApplyOutcome> change) {
        return newTransaction.execute(status -> {
            if (finishOutbox) {
                paymentOutboxPort.markDone(paymentId, ZonedDateTime.now());
            }
            Payment payment = paymentPort.findByIdForUpdate(paymentId).orElseThrow();
            return afterPaymentChanged(payment, change.apply(payment));
        });
    }

    private PaymentApplyOutcome afterPaymentChanged(Payment payment, PaymentApplyOutcome outcome) {
        paymentPort.save(payment);
        if (outcome == PaymentApplyOutcome.APPLIED) {
            applyToOrder(payment);
        }
        return outcome;
    }

    /**
     * SUCCESS: 주문 CONFIRMED + 선점 CONSUMED (추가 차감 없음). FAILED: 주문 PAYMENT_FAILED + 선점 RELEASED·재고 복구 (plan.md 3-3).
     */
    private void applyToOrder(Payment payment) {
        Long orderId = payment.getOrderId();
        OrderModel order = orderPort.findByIdForUpdate(orderId).orElseThrow();
        boolean success = payment.getStatus() == PaymentStatus.SUCCESS;
        boolean changed = success ? order.confirmPaid(ZonedDateTime.now()) : order.failPayment();
        if (!changed) {
            log.warn("결제는 종결됐지만 주문이 PAYMENT_PENDING이 아니라 바꾸지 않았습니다. orderId={}, status={}", orderId, order.getStatus());
        }
        orderPort.save(order);
        if (success) {
            stockReservationUseCase.consume(orderId);
        } else {
            stockReservationUseCase.release(orderId);
        }
    }
}
