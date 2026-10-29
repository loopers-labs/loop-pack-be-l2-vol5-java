package com.loopers.application.payment;

import com.loopers.application.order.OrderConfirmInfo;
import com.loopers.application.order.OrderInfo;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderConfirmedEvent;
import com.loopers.domain.order.OrderService;
import com.loopers.domain.payment.PaymentApplyResult;
import com.loopers.domain.payment.PaymentAttempt;
import com.loopers.domain.payment.PaymentAttemptRepository;
import com.loopers.domain.payment.PaymentFinalResult;
import com.loopers.domain.payment.PaymentGateway;
import com.loopers.domain.payment.PaymentLookupResult;
import com.loopers.domain.payment.PaymentResultService;
import com.loopers.domain.payment.PaymentTransaction;
import com.loopers.domain.user.UserRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

/**
 * callback·reconcile·스케줄러의 조율 (설계 6장). 세 입구 모두 "PG 권위 조회 → 네 항목 대조 → TX B(PaymentResultService) 전달"을 공유한다.
 * OrderFacade 와 서로 호출하지 않는다. 이 클래스의 메서드는 트랜잭션을 갖지 않는다. PG 조회 동안 DB 트랜잭션을 보유하지 않기 위해서다.
 * 어떤 경로에서도 create 는 다시 보내지 않는다.
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class PaymentFacade {

    private final UserRepository userRepository;
    private final OrderService orderService;
    private final PaymentAttemptRepository paymentAttemptRepository;
    private final PaymentResultService paymentResultService;
    private final PaymentGateway paymentGateway;
    private final PaymentLookupProperties lookupProperties;
    private final Clock clock;
    private final OrderConfirmedPublisher orderConfirmedPublisher;

    /**
     * POST /api/v1/payments/callback (설계 7-5 결정 ⑲·⑬).
     * 시도 없음 → NOT_FOUND. 종결 → 조회 없이 저장 결과. 그 외 목록 조회 후 반영.
     * 본문의 status 는 신뢰하지 않으므로 받지 않는다. transactionKey 는 목록의 거래와 대조하는 데만 쓴다.
     * 조회 실패·서킷 차단 → INTERNAL_ERROR(5xx), 상태 불변. 충돌(0건 포함) → CONFLICT(409) + 충돌 기록.
     */
    public OrderConfirmInfo applyCallback(String providerOrderId, String transactionKey) {
        PaymentAttempt attempt = paymentAttemptRepository.findByProviderOrderId(providerOrderId)
            .orElseThrow(() -> new CoreException(
                ErrorType.NOT_FOUND, "[providerOrderId = " + providerOrderId + "] 결제 시도를 찾을 수 없습니다."));

        if (!attempt.getStatus().isTerminal()) {
            PaymentLookupResult lookup = paymentGateway.findAllByOrderId(providerOrderId);
            if (lookup.isUnavailable()) {
                throw new CoreException(ErrorType.INTERNAL_ERROR, "PG 조회에 실패해 결제 결과를 확인하지 못했습니다.");
            }
            Optional<PaymentAttempt.ConflictReason> conflict = lookup.transactions().isEmpty()
                ? recordConflict(attempt, PaymentAttempt.ConflictReason.NO_TRANSACTION)
                : applyLookup(attempt, lookup.transactions(), transactionKey);
            if (conflict.isPresent()) {
                throw new CoreException(ErrorType.CONFLICT, "PG 조회 결과가 결제 시도와 일치하지 않습니다. (" + conflict.get() + ")");
            }
        }
        return toInfo(attempt.getOrderId(), false);
    }

    /**
     * POST /api/v1/orders/{orderId}/payment/reconcile (설계 5-6 결정 ⑳).
     * 사용자 식별 → 소유권 → 시도 없음(NOT_FOUND) → 종결이면 조회 없이 저장 결과 → 목록 조회 후 반영.
     * 조회 실패·서킷 차단·충돌은 예외가 아니라 lookupUnavailable=true 로 돌려준다. 조회 횟수(lookupCount)는 세지 않는다.
     */
    public OrderConfirmInfo reconcile(Long requesterId, Long orderId) {
        requireIdentifiedUser(requesterId);
        Order order = orderService.getOrder(orderId);
        if (!order.isOwnedBy(requesterId)) {
            throw new CoreException(ErrorType.NOT_FOUND, "[id = " + orderId + "] 주문을 찾을 수 없습니다.");
        }
        PaymentAttempt attempt = paymentAttemptRepository.findByOrderId(orderId)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "[orderId = " + orderId + "] 결제 시도를 찾을 수 없습니다."));

        boolean lookupUnavailable = !attempt.getStatus().isTerminal() && lookUp(attempt) != LookupOutcome.APPLIED;
        return toInfo(orderId, lookupUnavailable);
    }

    /**
     * 스케줄러 1회 실행 (설계 5-3 결정 ⑫). 미종결 + 최소 경과 시간이 지난 시도만 골라 건별로 조회·반영·조회 기록을 하고,
     * 한 건의 예외는 잡아 다음 건으로 넘어간다. @Transactional 을 걸지 않는다.
     * 조회 실패도, 예상 못 한 예외도 실제로 물어본 것이므로 lookupCount 를 1 올리고 nextLookupAt 을 갱신한다.
     * 서킷 차단(CIRCUIT_OPEN)은 물어보지 못한 것이므로 세지 않고, 같은 서킷을 쓰는 나머지도 막혀 있으므로 이번 주기를 끝낸다 (설계 5-7 결정 ㉙).
     */
    public void runScheduledLookups() {
        ZonedDateTime now = ZonedDateTime.now(clock);
        List<PaymentAttempt> dueAttempts = paymentAttemptRepository.findAllOpenDueForLookup(
            now.minus(lookupProperties.minAge()), now);

        for (PaymentAttempt attempt : dueAttempts) {
            try {
                if (lookUpScheduled(attempt, now) == LookupOutcome.CIRCUIT_OPEN) {
                    log.info("PG 조회 서킷이 열려 있어 이번 주기를 중단한다. 남은 대상은 다음 주기에 조회한다.");
                    return;
                }
            } catch (RuntimeException e) {
                log.warn("[attemptId = {}] 스케줄러 조회 중 예외가 났다. 다음 건을 계속한다.", attempt.getId(), e);
            }
        }
    }

    /**
     * 조회 뒤 조회 기록을 남긴다. 예외가 나도 기록한다. 서킷 차단만 기록하지 않는다.
     */
    private LookupOutcome lookUpScheduled(PaymentAttempt attempt, ZonedDateTime now) {
        LookupOutcome outcome;
        try {
            outcome = lookUp(attempt);
        } catch (RuntimeException e) {
            recordScheduledLookup(attempt, now);
            throw e;
        }
        if (outcome != LookupOutcome.CIRCUIT_OPEN) {
            recordScheduledLookup(attempt, now);
        }
        return outcome;
    }

    private void recordScheduledLookup(PaymentAttempt attempt, ZonedDateTime now) {
        int lookupCount = attempt.getLookupCount() + 1;
        paymentResultService.recordLookup(
            attempt.getId(), now.plus(intervalAfter(lookupCount)), lookupCount >= lookupProperties.maxLookups());
    }

    /**
     * 다음 조회까지의 간격 = min(initial-interval × multiplier^(lookupCount − 1), max-interval). 곱셈은 상한에 닿으면 멈춘다.
     */
    private Duration intervalAfter(int lookupCount) {
        Duration maxInterval = lookupProperties.maxInterval();
        Duration interval = lookupProperties.initialInterval();
        for (int i = 1; i < lookupCount && interval.compareTo(maxInterval) < 0; i++) {
            interval = interval.multipliedBy(lookupProperties.multiplier());
        }
        return interval.compareTo(maxInterval) > 0 ? maxInterval : interval;
    }

    /**
     * 조회 1건의 결과. APPLIED 는 조회가 되고 충돌이 없는 경우(0건·PENDING 전이·종결 반영 포함)다.
     * reconcile 은 APPLIED 가 아니면 lookupUnavailable=true 로 돌려주고, 스케줄러는 CIRCUIT_OPEN 이면 세지 않고 주기를 끝낸다.
     */
    private enum LookupOutcome {
        APPLIED, CONFLICT, UNAVAILABLE, CIRCUIT_OPEN
    }

    /**
     * 미종결 시도 1건을 PG 목록으로 확인해 반영한다. reconcile 과 스케줄러가 공유한다. 서킷 라이브러리는 모르고 결과 타입만 본다.
     */
    private LookupOutcome lookUp(PaymentAttempt attempt) {
        PaymentLookupResult lookup = paymentGateway.findAllByOrderId(attempt.getProviderOrderId());
        if (lookup.isCircuitOpen()) {
            return LookupOutcome.CIRCUIT_OPEN;
        }
        if (lookup.isUnavailable()) {
            return LookupOutcome.UNAVAILABLE;
        }
        return applyLookup(attempt, lookup.transactions(), null).isPresent() ? LookupOutcome.CONFLICT : LookupOutcome.APPLIED;
    }

    /**
     * 조회된 목록을 네 항목(설계 7-1)과 대조해, 정상 1건 일치면 TX B 에 넘기고 아니면 충돌을 기록한다 (7-6 표).
     * 0건은 여기서 충돌이 아니다. callback 입구의 0건 충돌은 호출하는 쪽이 정한다 (7-4 결정 ⑨).
     *
     * @param notifiedTransactionKey callback 본문의 transactionKey. reconcile·스케줄러는 null
     * @return 기록한 충돌 사유. 충돌이 없으면 empty
     */
    private Optional<PaymentAttempt.ConflictReason> applyLookup(
        PaymentAttempt attempt, List<PaymentTransaction> transactions, String notifiedTransactionKey
    ) {
        Optional<PaymentAttempt.ConflictReason> conflict = conflictOf(attempt, transactions, notifiedTransactionKey);
        if (conflict.isPresent()) {
            return recordConflict(attempt, conflict.get());
        }
        if (!transactions.isEmpty()) {
            apply(attempt, transactions.get(0));
        }
        return Optional.empty();
    }

    private Optional<PaymentAttempt.ConflictReason> conflictOf(
        PaymentAttempt attempt, List<PaymentTransaction> transactions, String notifiedTransactionKey
    ) {
        if (transactions.size() > 1) {
            return Optional.of(PaymentAttempt.ConflictReason.MULTIPLE_TRANSACTIONS);
        }
        if (transactions.isEmpty()) {
            return Optional.empty();
        }
        PaymentTransaction transaction = transactions.get(0);
        if (!transaction.providerOrderId().equals(attempt.getProviderOrderId())) {
            return Optional.of(PaymentAttempt.ConflictReason.ORDER_MISMATCH);
        }
        if (attempt.getTransactionKey() != null && !attempt.getTransactionKey().equals(transaction.transactionKey())) {
            return Optional.of(PaymentAttempt.ConflictReason.TRANSACTION_KEY_MISSING);
        }
        if (notifiedTransactionKey != null && !notifiedTransactionKey.equals(transaction.transactionKey())) {
            return Optional.of(PaymentAttempt.ConflictReason.TRANSACTION_KEY_MISSING);
        }
        if (transaction.amount() != attempt.getAmount()) {
            return Optional.of(PaymentAttempt.ConflictReason.AMOUNT_MISMATCH);
        }
        return Optional.empty();
    }

    private Optional<PaymentAttempt.ConflictReason> recordConflict(PaymentAttempt attempt, PaymentAttempt.ConflictReason reason) {
        paymentResultService.recordConflict(attempt.getId(), reason);
        return Optional.of(reason);
    }

    /**
     * 검증된 거래 1건을 TX B 에 넘긴다. SUCCESS·FAILED 는 ⑥⑦⑧, PENDING 은 PENDING 전이 + key 저장(결정 ⑪)만 한다.
     * SUCCESS 가 주문을 처음 CONFIRMED 로 만들었으면, applyFinal 이 리턴한 뒤(= TX B commit 뒤) OrderConfirmed 를 발행한다 (6주차 설계 D4).
     * applyFinal 의 예외는 그대로 전파된다. 발행만 감싼다.
     */
    private void apply(PaymentAttempt attempt, PaymentTransaction transaction) {
        switch (transaction.status()) {
            case SUCCESS -> {
                PaymentApplyResult applied = paymentResultService.applyFinal(
                    attempt.getId(), PaymentFinalResult.success(transaction.transactionKey()));
                if (applied.isFirstConfirmation()) {
                    publishAfterCommit(applied.firstConfirmation());
                }
            }
            case FAILED -> paymentResultService.applyFinal(
                attempt.getId(), PaymentFinalResult.failed(PaymentAttempt.FailureReason.PG_FAILED, transaction.transactionKey()));
            case PENDING -> paymentResultService.recordPending(attempt.getId(), transaction.transactionKey());
        }
    }

    /**
     * 이 Facade 에는 TX 가 없으므로 여기 도달한 시점은 확정 TX 가 이미 commit 된 뒤다.
     * 발행 실패 확정·미확인·예상 못한 오류는 ERROR 로그로만 드러내고 밖으로 던지지 않는다. 주문 상태를 되돌리거나 PG 를 재호출하지 않는다 (D7).
     * commit 뒤 ~ 발행 사이의 누락은 이 구조로는 막을 수 없다 → W7 Outbox.
     */
    private void publishAfterCommit(OrderConfirmedEvent event) {
        try {
            orderConfirmedPublisher.publish(event);
        } catch (OrderConfirmedPublishException e) {
            log.error("OrderConfirmed 발행 {}. outcome={}, eventId={}, orderId={}. 주문은 CONFIRMED 로 유지한다.",
                e.getOutcome() == OrderConfirmedPublishException.Outcome.FAILED ? "실패 확정" : "결과 미확인",
                e.getOutcome(), e.getEventId(), e.getOrderId(), e);
        } catch (RuntimeException e) {
            log.error("OrderConfirmed 발행 중 예상 못한 오류. eventId={}, orderId={}. 주문은 CONFIRMED 로 유지한다.",
                event.eventId(), event.orderId(), e);
        }
    }

    /**
     * 반영이 끝난 뒤 주문(품목 포함)·시도를 다시 읽어 응답을 만든다. transactionKey 는 담지 않는다 (설계 4-6).
     */
    private OrderConfirmInfo toInfo(Long orderId, boolean lookupUnavailable) {
        Order order = orderService.getOrderWithItems(orderId);
        PaymentAttempt attempt = paymentAttemptRepository.findByOrderId(orderId)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "[orderId = " + orderId + "] 결제 시도를 찾을 수 없습니다."));
        return new OrderConfirmInfo(
            OrderInfo.from(order), attempt.getStatus().name(), attempt.getStatus().isTerminal(), lookupUnavailable);
    }

    private void requireIdentifiedUser(Long userId) {
        if (userId == null || !userRepository.existsById(userId)) {
            throw new CoreException(ErrorType.UNAUTHORIZED, "사용자 식별에 실패했습니다.");
        }
    }
}
