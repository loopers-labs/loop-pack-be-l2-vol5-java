package com.loopers.application.payment;

import com.loopers.application.order.OrderConfirmedPayload;
import com.loopers.application.order.OrderConfirmedPublication;
import com.loopers.application.order.OrderConfirmedUnpublished;
import com.loopers.application.product.ProductCacheInvalidator;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderService;
import com.loopers.domain.payment.ApplyResult;
import com.loopers.domain.payment.PaymentAttempt;
import com.loopers.domain.payment.PaymentAttemptRepository;
import com.loopers.domain.payment.PaymentAttemptStatus;
import com.loopers.domain.payment.PaymentConflict;
import com.loopers.domain.payment.PaymentConflictReason;
import com.loopers.domain.payment.PaymentConflictRepository;
import com.loopers.domain.payment.PaymentObservation;
import com.loopers.domain.product.ProductService;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.Optional;

/**
 * 결과 반영(TX B) — 결과가 들어오는 모든 입구(confirm·reconcile·callback 워커·스케줄러·관리자)가 지나는 경계다
 * (docs/week5/design.md 3번). 결제 시도·주문·예약을 한 트랜잭션에서 바꾼다(INV-001).
 *
 * - 락 순서: 주문 → 결제 시도 → 상품(id 오름차순)(D-002).
 * - 전이 판단은 PaymentAttempt가 하고, 여기서는 전이가 일어났을 때만 효과를 낸다(INV-002, D-001).
 * - PG 호출은 하지 않는다. 호출자가 트랜잭션 밖에서 조회한 결과를 넘긴다.
 * - 처음 SUCCEEDED로 전이한 경우에만 OrderConfirmed 발행을 afterCommit에 등록한다(docs/week6/design.md INV-601, decisions.md D-2).
 *   모든 결과 입구가 여기를 지나므로 발행 조건은 이 한 곳에 있다.
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class PaymentResultApplier {

    private final OrderService orderService;
    private final PaymentAttemptRepository paymentAttemptRepository;
    private final ProductService productService;
    private final ProductCacheInvalidator productCacheInvalidator;
    private final PaymentConflictRepository paymentConflictRepository;
    private final OrderConfirmedPublication orderConfirmedPublication;
    private final OrderConfirmedUnpublished orderConfirmedUnpublished;

    /** PG 권위 조회로 확인한 거래를 반영한다. 어긋나면 상태는 그대로 두고 충돌로 기록한다(INV-003, D-010). */
    @Transactional
    public ApplyResult apply(Long orderId, PaymentObservation observation) {
        Locked locked = lock(orderId);
        PaymentAttempt attempt = locked.attempt();
        ApplyResult result = attempt.apply(observation);
        if (result == ApplyResult.CONFLICT) {
            recordConflict(attempt, attempt.conflictReasonFor(observation), observation);
        }
        applyEffect(locked.order(), attempt, result);
        return result;
    }

    /**
     * 관리자가 PG에 거래가 없음을 확인하고 실패로 처리한다(D-009, D-045). 다른 실패 반영과 같이 주문 실패·예약 반환을
     * 같은 트랜잭션에서 하고(INV-001), 감시를 시작한다(D-013).
     */
    @Transactional
    public void failByAdmin(Long orderId, String resolvedBy, ZonedDateTime now) {
        Locked locked = lock(orderId);
        ApplyResult result = locked.attempt().failByAdmin(resolvedBy, now);
        applyEffect(locked.order(), locked.attempt(), result);
    }

    /** 같은 주문 식별자로 거래가 2건 이상 조회됐다 — 자동으로 고르지 않고 상태를 그대로 둔 채 기록한다(D-010). */
    @Transactional
    public void recordMultipleTransactions(Long orderId) {
        PaymentAttempt attempt = lock(orderId).attempt();
        recordConflict(attempt, PaymentConflictReason.MULTIPLE_TRANSACTIONS, null);
    }

    /** 정확한 조건의 422 — 효과 0건이 확정된 거절이라 바로 FAILED로 종결하고 예약을 돌려준다. */
    @Transactional
    public ApplyResult rejectBeforeCreate(Long orderId) {
        Locked locked = lock(orderId);
        ApplyResult result = locked.attempt().rejectBeforeCreate();
        applyEffect(locked.order(), locked.attempt(), result);
        return result;
    }

    /** create 202 PENDING — transactionKey만 기록한다. 주문·재고는 바뀌지 않는다. */
    @Transactional
    public ApplyResult recordAccepted(Long orderId, String transactionKey) {
        PaymentAttempt attempt = lock(orderId).attempt();
        ApplyResult result = attempt.accept(transactionKey);
        if (result == ApplyResult.CONFLICT) {
            // 먼저 반영된 거래와 다른 키 — 수락 응답이 말한 거래를 그대로 남긴다.
            recordConflict(attempt, PaymentConflictReason.MISMATCH, new PaymentObservation(
                attempt.getProviderOrderId(), transactionKey, PaymentObservation.Status.PENDING, attempt.getAmount()));
        }
        return result;
    }

    /**
     * 회복 작업의 조회 결과로 다음 확인 일정을 적는다(D-043, D-044). 고객 경로는 부르지 않는다 — 0건 횟수는 회복 작업만 센다.
     * 결과 반영(apply)과 별개의 짧은 트랜잭션이다. 같은 락 순서(주문 → 시도)를 따른다.
     */
    @Transactional
    public void recordLookup(Long orderId, PaymentReconciler.Outcome outcome, ZonedDateTime now) {
        PaymentAttempt attempt = lock(orderId).attempt();
        if (attempt.getStatus().isTerminal()) {
            // 감시 중인 종결 시도(D-013) — 0건 횟수와 상관없이 6시간 뒤 다시 본다.
            if (attempt.isWatched(now)) {
                attempt.recordWatchCheck(now);
            }
            return;
        }
        switch (outcome) {
            case NOT_FOUND -> attempt.recordEmptyLookup(now);
            case APPLIED, MULTIPLE_TRANSACTIONS -> attempt.recordFound(now);
            case UNAVAILABLE -> attempt.recordUnavailable(now);
        }
    }

    /** create 응답을 받지 못했다 — 실패가 아니라 "모른다"(INV-004). */
    @Transactional
    public void recordUnknown(Long orderId) {
        lock(orderId).attempt().markUnknown();
    }

    private Locked lock(Long orderId) {
        Order order = orderService.getOrderForUpdate(orderId);
        PaymentAttempt attempt = paymentAttemptRepository.findByOrderIdForUpdate(orderId)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "[orderId = " + orderId + "] 결제 시도를 찾을 수 없습니다."));
        return new Locked(order, attempt);
    }

    private void applyEffect(Order order, PaymentAttempt attempt, ApplyResult result) {
        switch (result) {
            case SUCCEEDED -> {
                order.completePayment(attempt.getAmount());
                registerOrderConfirmed(order);
            }
            case FAILED -> {
                order.failPayment();
                // 예약 반환 — 지금 값에 더한다(INV-006), 삭제된 상품은 건너뛴다(D-019). 상품 락은 주문·시도 다음, id 오름차순.
                order.getItems().stream()
                    .sorted(Comparator.comparing(OrderItem::getProductId))
                    .forEach(item -> productService.returnReservedStock(item.getProductId(), item.getQuantity()));
                productCacheInvalidator.productsChanged(order.getItems().stream()
                    .map(OrderItem::getProductId)
                    .toList());
            }
            case NO_EFFECT, CONFLICT -> {
                // 상태를 바꾸지 않는다(INV-003).
            }
        }
    }

    /**
     * 확정 직후, 같은 트랜잭션 안에서 payload를 불변 객체로 만들어 등록한다(docs/week6/decisions.md D-5). 실제 발행은 commit 뒤다.
     * 발행 준비가 실패해도 확정은 되돌리지 않는다(docs/week6/decisions.md D-26) — PG는 이미 SUCCESS라 롤백하면
     * "결제됐는데 미확정"이 남고, 이 자리의 예외는 대부분 버그라 회복 작업이 재시도해도 매번 롤백된다. 잃는 건 주문 DB에서
     * 다시 만들 수 있는 이벤트 하나다(D-1의 누락 쪽). 대신 미발행으로 기록하고 Counter(reason=register_failed)로 센다.
     */
    private void registerOrderConfirmed(Order order) {
        try {
            orderConfirmedPublication.register(OrderConfirmedPayload.from(order));
        } catch (RuntimeException e) {
            orderConfirmedUnpublished.record(OrderConfirmedUnpublished.Reason.REGISTER_FAILED,
                order.getId(), OrderConfirmedPayload.eventIdOf(order.getId()), e);
        }
    }

    /**
     * 같은 내용의 충돌이 이미 있으면 새 행을 만들지 않는다 — 처리 전이면 마지막 발견 시각만 갱신하고, 처리 후면 무시한다.
     * 내용이 다르면 새 행이다(D-012). 결제 시도 행을 잠근 뒤 부르므로 동시에 발견된 같은 충돌도 한 행으로 모인다.
     */
    private void recordConflict(PaymentAttempt attempt, PaymentConflictReason reason, PaymentObservation observation) {
        PaymentAttemptStatus recordedStatus = attempt.getStatus();
        Optional<PaymentConflict> same = paymentConflictRepository.findAllByAttemptId(attempt.getId()).stream()
            .filter(conflict -> conflict.hasSameContentAs(reason, recordedStatus, observation))
            .findFirst();
        if (same.isEmpty()) {
            paymentConflictRepository.save(new PaymentConflict(attempt.getId(), reason, recordedStatus, observation));
            log.warn("결제 충돌 발견: attemptId={}, reason={}, recordedStatus={}, observed={}",
                attempt.getId(), reason, recordedStatus, observation);
            return;
        }
        if (!same.get().isResolved()) {
            same.get().detectedAgain();
        }
    }

    private record Locked(Order order, PaymentAttempt attempt) {}
}
