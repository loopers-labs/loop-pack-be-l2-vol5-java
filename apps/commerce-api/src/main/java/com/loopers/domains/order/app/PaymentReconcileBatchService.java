package com.loopers.domains.order.app;

import com.loopers.common.error.InvalidValueException;
import com.loopers.domains.order.app.ReconcileBatchResult.Assessment;
import com.loopers.domains.order.app.ReconcileBatchResult.Item;
import com.loopers.domains.order.app.ReconcileBatchResult.Outcome;
import com.loopers.domains.order.app.ReconcileBatchResult.StopReason;
import com.loopers.domains.order.app.port.ReconcilePacer;
import com.loopers.domains.order.domain.Order;
import com.loopers.domains.payment.app.PaymentLookupNotCalledException;
import com.loopers.domains.payment.app.port.PaymentAnomalyRepository.Kind;
import com.loopers.domains.payment.app.port.PaymentLookupCircuitState;
import com.loopers.domains.payment.app.port.PaymentRecordRepository;
import com.loopers.domains.payment.domain.PaymentRecord;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

// 사람이 고정한 orderId 목록만 처리한다. 주문마다 수동 reconcile과 같은 경로(같은 거래 조회 → 기존 반영 경계)를 타며 create는 부르지 않는다.
// 이미 종결·대상 아님은 PG를 조회하지 않는다(PG 부하를 줄이려는 선택으로, 종결 주문도 조회하는 단건 reconcile과 다르다).
@Slf4j
@Service
public class PaymentReconcileBatchService {
    private static final String NOT_FOUND = "NOT_FOUND";
    private static final String NOT_PG = "NOT_PG";
    private static final String STILL_PENDING = "STILL_PENDING";
    private static final String LOOKUP_NOT_CALLED = "LOOKUP_NOT_CALLED";
    private static final String NOT_ASSESSED = "NOT_ASSESSED";
    private static final String PROCESSING_FAILED = "PROCESSING_FAILED";

    private final OrderApplicationService orderApplicationService;
    private final PaymentRecordRepository paymentRecordRepository;
    private final PaymentLookupCircuitState paymentLookupCircuitState;
    private final ReconcilePacer reconcilePacer;
    private final ReconcileBatchPolicy reconcileBatchPolicy;

    public PaymentReconcileBatchService(OrderApplicationService orderApplicationService,
        PaymentRecordRepository paymentRecordRepository, PaymentLookupCircuitState paymentLookupCircuitState,
        ReconcilePacer reconcilePacer, ReconcileBatchPolicy reconcileBatchPolicy) {
        this.orderApplicationService = orderApplicationService;
        this.paymentRecordRepository = paymentRecordRepository;
        this.paymentLookupCircuitState = paymentLookupCircuitState;
        this.reconcilePacer = reconcilePacer;
        this.reconcileBatchPolicy = reconcileBatchPolicy;
    }

    public ReconcileBatchResult reconcile(String requester, List<Long> orderIds) {
        // 한도는 보낸 목록 길이(중복 포함)로 본다. 중복은 처음 위치에서 한 번만 처리한다.
        if (orderIds == null || orderIds.isEmpty() || orderIds.size() > reconcileBatchPolicy.maxOrders()) {
            throw new InvalidValueException("orderIds는 1~" + reconcileBatchPolicy.maxOrders() + "건이어야 합니다.");
        }
        List<Long> distinct = List.copyOf(new LinkedHashSet<>(orderIds));
        rejectWhileLookupBlocked(requester, distinct);
        log.info("관리자 일괄 조회 확정을 시작합니다. requester={}, requested={}", requester, distinct.size());
        List<Item> results = new ArrayList<>();
        StopReason stopReason = null;
        int consecutiveFailures = 0;
        boolean lookedUp = false;
        for (long orderId : distinct) {
            if (stopReason != null) {
                results.add(new Item(orderId, null, null, null, null, null, Outcome.SKIPPED, stopReason.name()));
                continue;
            }
            Optional<OrderResult> found = orderApplicationService.findAdminOrder(orderId);
            Item item;
            if (found.isEmpty()) {
                item = new Item(orderId, null, null, null, null, null, Outcome.NOT_APPLICABLE, NOT_FOUND);
            } else if (!PaymentRecord.Method.PG.name().equals(found.get().paymentMethod())) {
                item = unchanged(found.get(), Outcome.NOT_APPLICABLE, NOT_PG);
            } else if (!Order.Status.PAYMENT_PENDING.name().equals(found.get().status())) {
                item = unchanged(found.get(), Outcome.ALREADY_CLOSED, null);
            } else {
                if (lookedUp) {
                    reconcilePacer.pause(reconcileBatchPolicy.interval());
                }
                lookedUp = true;
                item = reconcilePending(found.get());
            }
            results.add(item);
            consecutiveFailures = nextConsecutiveFailures(consecutiveFailures, item);
            if (consecutiveFailures >= reconcileBatchPolicy.consecutiveFailureLimit()) {
                stopReason = StopReason.CONSECUTIVE_FAILURES;
            }
        }
        ReconcileBatchResult result = new ReconcileBatchResult(distinct.size(),
            (int) results.stream().filter(item -> item.outcome() != Outcome.SKIPPED).count(), stopReason, results);
        log.info("관리자 일괄 조회 확정을 마쳤습니다. requester={}, requested={}, processed={}, outcomes={}, stopReason={}",
            requester, result.requested(), result.processed(), countByOutcome(results), stopReason);
        return result;
    }

    // 목록 주문들의 PG 중 하나라도 조회 회로가 막혀 있으면 하나도 처리하지 않는다. 막힌 PG를 다시 몰아붙이지 않기 위해서다.
    private void rejectWhileLookupBlocked(String requester, List<Long> orderIds) {
        List<String> blocked = paymentRecordRepository.findByOrderIds(orderIds).stream()
            .filter(payment -> payment.getMethod() == PaymentRecord.Method.PG)
            .map(PaymentRecord::getProviderId)
            .filter(Objects::nonNull)
            .distinct()
            .filter(paymentLookupCircuitState::blocksLookup)
            .toList();
        if (!blocked.isEmpty()) {
            log.warn("조회 회로가 열려 관리자 일괄 조회 확정을 시작하지 않았습니다. requester={}, requested={}, providerIds={}",
                requester, orderIds.size(), blocked);
            throw new PaymentLookupNotCalledException();
        }
    }

    private Item reconcilePending(OrderResult before) {
        PaymentReconciliation reconciliation;
        try {
            reconciliation = orderApplicationService.reconcileForOperator(before.id());
        } catch (PaymentLookupNotCalledException e) {
            return unchanged(before, Outcome.STOPPED, Assessment.NOT_CALLED, LOOKUP_NOT_CALLED);
        } catch (RuntimeException e) {
            // 반영 실패는 수동 reconcile 경로가 주문 열쇠와 함께 이미 남겼다. 나머지 주문은 계속 처리하고 이 주문만 멈춤으로 둔다.
            OrderResult after = orderApplicationService.findAdminOrder(before.id()).orElse(before);
            return item(before, after, null, Outcome.STOPPED, PROCESSING_FAILED);
        }
        OrderResult after = reconciliation.result();
        boolean stillPending = Order.Status.PAYMENT_PENDING.name().equals(after.status());
        return switch (reconciliation.assessment()) {
            case PaymentLookupAssessment.Verified verified -> stillPending
                ? item(before, after, Assessment.VERIFIED, Outcome.STOPPED, STILL_PENDING)
                : item(before, after, Assessment.VERIFIED, Outcome.RESOLVED, null);
            case PaymentLookupAssessment.Uncertain uncertain ->
                item(before, after, Assessment.UNCERTAIN, Outcome.STOPPED, uncertain.kind().name());
            case PaymentLookupAssessment.Review review ->
                item(before, after, Assessment.REVIEW, Outcome.STOPPED, review.kind().name());
            case PaymentLookupAssessment.NotCalled notCalled ->
                item(before, after, Assessment.NOT_CALLED, Outcome.STOPPED, LOOKUP_NOT_CALLED);
            case null -> stillPending
                ? item(before, after, null, Outcome.STOPPED, NOT_ASSESSED)
                : item(before, after, null, Outcome.ALREADY_CLOSED, null);
        };
    }

    // 연속 실패는 PG 상태 신호(조회 실패·회로로 미호출)만 센다. 종결·이미 종결이 나오면 다시 0부터 센다. 나머지는 영향이 없다.
    private static int nextConsecutiveFailures(int current, Item item) {
        if (item.outcome() == Outcome.RESOLVED || item.outcome() == Outcome.ALREADY_CLOSED) {
            return 0;
        }
        boolean lookupFailed = item.assessment() == Assessment.UNCERTAIN
            && Kind.LOOKUP_FAILED.name().equals(item.reason());
        if (item.assessment() == Assessment.NOT_CALLED || lookupFailed) {
            return current + 1;
        }
        return current;
    }

    private static Item unchanged(OrderResult order, Outcome outcome, String reason) {
        return unchanged(order, outcome, null, reason);
    }

    private static Item unchanged(OrderResult order, Outcome outcome, Assessment assessment, String reason) {
        return item(order, order, assessment, outcome, reason);
    }

    private static Item item(OrderResult before, OrderResult after, Assessment assessment, Outcome outcome, String reason) {
        return new Item(before.id(), before.status(), after.status(), before.providerStatus(), after.providerStatus(),
            assessment, outcome, reason);
    }

    private static Map<Outcome, Long> countByOutcome(List<Item> results) {
        Map<Outcome, Long> counts = new EnumMap<>(Outcome.class);
        results.forEach(item -> counts.merge(item.outcome(), 1L, Long::sum));
        return counts;
    }
}
