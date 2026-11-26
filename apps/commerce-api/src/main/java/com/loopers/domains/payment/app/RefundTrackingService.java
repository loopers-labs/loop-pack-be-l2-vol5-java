package com.loopers.domains.payment.app;

import com.loopers.common.error.RuleViolationException;
import com.loopers.domains.payment.app.port.LookupJitter;
import com.loopers.domains.payment.app.port.PaymentCancelCommand;
import com.loopers.domains.payment.app.port.PaymentCancelResult;
import com.loopers.domains.payment.app.port.PaymentLookupCircuitState;
import com.loopers.domains.payment.app.port.PaymentRefundCaseRepository;
import com.loopers.domains.payment.app.port.PaymentTransaction;
import com.loopers.domains.payment.app.port.PaymentTransactionsLookup;
import com.loopers.domains.payment.app.port.RefundOutcomeRecorder;
import com.loopers.domains.payment.app.port.RefundOutcomeRecorder.Result;
import com.loopers.domains.payment.domain.RefundCase;
import com.loopers.domains.payment.domain.RefundCase.CheckOutcome;
import com.loopers.domains.payment.domain.RefundCase.CheckTrigger;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

// 만료된 PG 결제의 환불 확인 대상을 결론까지 끌고 간다(10주차 5단계 계획 2-1·2-2). 환불(PG 취소)은 이 서비스 한 곳에서만 보낸다.
// 대상마다 트랜잭션을 나눈다: 잠금 아래 기한·회로 판단 → 잠금 밖 PG 조회 → 잠금 아래 다시 읽고 반영 → (환불 요청 commit 뒤) 취소 → 결과 반영.
// 대상 행 잠금이 늦은 성공 합류(OrderApplicationService)와 다른 인스턴스의 회차를 직렬화한다. 잠금 아래 상태가 바뀌었으면 반영하지 않는다.
// 관리자의 "지금 처리"(10주차 5c)도 같은 회차를 대상 하나에 돌린다. 회차마다 마지막 확인(시각·분류·원인)을 대상에 남긴다.
@Slf4j
@Service
public class RefundTrackingService {
    // [임시] 한 회차에 다루는 대상 수 상한이다(계획 2-2). PG가 회복 중일 때 조회를 몰아붙이지 않기 위해서다.
    public static final int BATCH_SIZE = 20;
    private static final String ORDER_ID_KEY = "orderId";

    private final PaymentRefundCaseRepository paymentRefundCaseRepository;
    private final PaymentGatewayRegistry paymentGatewayRegistry;
    private final PaymentLookupCircuitState paymentLookupCircuitState;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final RefundOutcomeRecorder refundOutcomeRecorder;
    private final LookupJitter lookupJitter;
    private final RefundCheckFailureRecorder refundCheckFailureRecorder;

    public RefundTrackingService(PaymentRefundCaseRepository paymentRefundCaseRepository,
        PaymentGatewayRegistry paymentGatewayRegistry, PaymentLookupCircuitState paymentLookupCircuitState,
        TransactionTemplate transactionTemplate, Clock clock, RefundOutcomeRecorder refundOutcomeRecorder,
        LookupJitter lookupJitter, RefundCheckFailureRecorder refundCheckFailureRecorder) {
        this.paymentRefundCaseRepository = paymentRefundCaseRepository;
        this.paymentGatewayRegistry = paymentGatewayRegistry;
        this.paymentLookupCircuitState = paymentLookupCircuitState;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
        this.refundOutcomeRecorder = refundOutcomeRecorder;
        this.lookupJitter = lookupJitter;
        this.refundCheckFailureRecorder = refundCheckFailureRecorder;
    }

    // 사람 담당 중인 대상 하나에 스케줄러와 같은 회차를 다음 조회 시각과 무관하게 지금 돌리고 그 결과를 돌려준다(10주차 5c).
    // 담당·돈 검사는 회차 첫 잠금 아래에서 한다. 새 타이머·기한은 만들지 않고, 끝나지 않으면 사람 담당 그대로 원인만 갱신된다.
    public ProcessNowResult processNow(long orderId, String adminName) {
        String previousOrderId = MDC.get(ORDER_ID_KEY);
        MDC.put(ORDER_ID_KEY, String.valueOf(orderId));
        try {
            track(orderId, new Round(CheckTrigger.PERSON, adminName));
        } finally {
            restoreOrderId(previousOrderId);
        }
        RefundCase refundCase = paymentRefundCaseRepository.findByOrderId(orderId).orElseThrow(RefundCaseNotFoundException::new);
        return new ProcessNowResult(refundCase.getOrderId(), refundCase.getMoneyStatus().name(),
            refundCase.getRefundActor() == null ? null : refundCase.getRefundActor().name(), refundCase.getOwner().name(),
            refundCase.getOwnerClosedAt(), refundCase.getOwnerClosedBy(), refundCase.getLastCheckAt(),
            refundCase.getLastCheckOutcome() == null ? null : refundCase.getLastCheckOutcome().name(),
            refundCase.getLastCheckCause());
    }

    public void trackDueCases() {
        List<Long> dueOrders = paymentRefundCaseRepository.findDue(clock.instant(), BATCH_SIZE);
        for (long orderId : dueOrders) {
            String previousOrderId = MDC.get(ORDER_ID_KEY);
            MDC.put(ORDER_ID_KEY, String.valueOf(orderId));
            try {
                track(orderId, Round.SCHEDULED);
            } catch (RuntimeException e) {
                log.error("환불 추적 처리 실패. orderId={}, causeType={}", orderId, e.getClass().getSimpleName());
            } finally {
                restoreOrderId(previousOrderId);
            }
        }
    }

    private void track(long orderId, Round round) {
        RefundCase snapshot = transactionTemplate.execute(status -> prepareLookup(orderId, round));
        if (snapshot == null) {
            return;
        }
        PaymentTransactionsLookup lookup = lookUp(snapshot);
        PaymentCancelCommand cancelCommand = applyLookupOrRecordFailure(snapshot, lookup, round);
        if (cancelCommand == null) {
            return;
        }
        log.info("환불을 요청합니다. orderId={}, providerId={}", orderId, snapshot.getProviderId());
        PaymentCancelResult result = cancel(snapshot, cancelCommand);
        transactionTemplate.executeWithoutResult(status -> applyCancelResult(orderId, result, round));
    }

    // 반영 트랜잭션이 rollback된 뒤(잠금이 풀린 뒤)에 별도 트랜잭션으로 원인만 남긴다. 반영 트랜잭션 안에서 남기면 함께 사라진다.
    // 원인 기록이 실패해도 원래 예외를 덮지 않는다(PL P3).
    private PaymentCancelCommand applyLookupOrRecordFailure(RefundCase snapshot, PaymentTransactionsLookup lookup, Round round) {
        try {
            return transactionTemplate.execute(status -> applyLookup(snapshot, lookup, round));
        } catch (RuntimeException e) {
            try {
                refundCheckFailureRecorder.recordApplyFailed(snapshot.getOrderId(), e.getClass().getSimpleName());
            } catch (RuntimeException recordFailure) {
                log.error("환불 확인 반영 실패 원인을 남기지 못했습니다. orderId={}, causeType={}", snapshot.getOrderId(),
                    recordFailure.getClass().getSimpleName());
            }
            throw e;
        }
    }

    // 취소 호출 예외는 요청이 나갔는지 모르는 경우다. REFUND_REQUESTED로 두지 않고 결과 모름으로 넘겨 알림(PaymentRefundUnknown)이 보게 한다.
    private PaymentCancelResult cancel(RefundCase snapshot, PaymentCancelCommand cancelCommand) {
        try {
            return paymentGatewayRegistry.get(snapshot.getProviderId()).cancel(cancelCommand);
        } catch (RuntimeException e) {
            log.warn("환불 취소 호출이 예외로 끝나 결과 모름으로 둡니다. orderId={}, causeType={}", snapshot.getOrderId(),
                e.getClass().getSimpleName());
            return new PaymentCancelResult.Unknown(e.getClass().getSimpleName());
        }
    }

    // 기한이 지난 자동 담당 대상은 PG를 부르지 않고 담당만 사람에게 넘긴다(돈의 상태는 그대로). 사람 담당이어도 돈이 끝나지 않았으면
    // 계속 조회한다. 조회 회로가 막힌 PG의 대상은 PG를 다시 쓰러뜨리지 않도록 조회 없이 미룬다.
    // 사람이 누른 회차는 다음 조회 시각을 보지 않는 대신 사람 담당 중·돈 비종결인 대상만 받는다.
    private RefundCase prepareLookup(long orderId, Round round) {
        Optional<RefundCase> found = paymentRefundCaseRepository.findByOrderIdForUpdate(orderId);
        Instant now = clock.instant();
        if (round.trigger() == CheckTrigger.PERSON) {
            requirePersonOpen(found.orElseThrow(RefundCaseNotFoundException::new));
        } else if (found.isEmpty() || !isDue(found.get(), now)) {
            return null;
        }
        RefundCase refundCase = found.get();
        if (refundCase.isHandoffDue(now)) {
            assignPerson(refundCase, RefundCase.HANDOFF_DEADLINE, now);
            paymentRefundCaseRepository.save(refundCase);
            return null;
        }
        if (paymentLookupCircuitState.blocksLookup(refundCase.getProviderId())) {
            refundCase.postponeCheck(now, jittered(refundCase.postponeCheckCeiling()));
            recordCheck(refundCase, CheckOutcome.PG_UNREACHABLE, null, now);
            paymentRefundCaseRepository.save(refundCase);
            return null;
        }
        return refundCase;
    }

    private static void requirePersonOpen(RefundCase refundCase) {
        if (refundCase.isConcluded()) {
            throw new RuleViolationException("돈의 상태가 이미 끝난 환불 확인 대상입니다: " + refundCase.getMoneyStatus());
        }
        if (!refundCase.isPersonOpen()) {
            throw new RuleViolationException("사람 담당 중인 환불 확인 대상이 아닙니다.");
        }
    }

    private static boolean isDue(RefundCase refundCase, Instant now) {
        return !refundCase.isConcluded() && refundCase.getNextCheckAt() != null && !refundCase.getNextCheckAt().isAfter(now);
    }

    // 조회 예외는 조회 실패와 같다. 결론 근거가 아니므로 다음 조회로 넘긴다.
    private PaymentTransactionsLookup lookUp(RefundCase refundCase) {
        try {
            return paymentGatewayRegistry.get(refundCase.getProviderId())
                .verifyOrder(refundCase.getProviderOrderId(), refundCase.getTransactionKey());
        } catch (RuntimeException e) {
            log.warn("환불 확인 조회가 예외로 끝나 다음 조회로 넘깁니다. orderId={}, causeType={}", refundCase.getOrderId(),
                e.getClass().getSimpleName());
            return new PaymentTransactionsLookup.Failed(e.getClass().getSimpleName());
        }
    }

    // 환불 요청함으로 바꾸면 취소 명령을 돌려준다. 호출부는 이 트랜잭션이 commit된 뒤에만 취소를 보낸다.
    // 조회 동안 돈의 상태나 담당이 바뀌었으면(다른 인스턴스·합류·이관) 읽어 둔 판단으로 반영하지 않는다.
    // 조회 동안 이관 기한이 지났으면 담당을 먼저 사람에게 넘긴 뒤 결과를 반영한다. 그래야 기한 경계에서 사람 담당 건에 취소가 나가지 않는다.
    private PaymentCancelCommand applyLookup(RefundCase snapshot, PaymentTransactionsLookup lookup, Round round) {
        Optional<RefundCase> found = paymentRefundCaseRepository.findByOrderIdForUpdate(snapshot.getOrderId());
        if (found.isEmpty() || found.get().getMoneyStatus() != snapshot.getMoneyStatus()
            || found.get().getOwner() != snapshot.getOwner() || found.get().isConcluded()) {
            return null;
        }
        RefundCase refundCase = found.get();
        Instant now = clock.instant();
        if (refundCase.isHandoffDue(now)) {
            assignPerson(refundCase, RefundCase.HANDOFF_DEADLINE, now);
        }
        PaymentCancelCommand cancelCommand = null;
        switch (lookup) {
            case PaymentTransactionsLookup.NotCalled notCalled -> {
                refundCase.postponeCheck(now, jittered(refundCase.postponeCheckCeiling()));
                recordCheck(refundCase, CheckOutcome.PG_UNREACHABLE, null, now);
            }
            case PaymentTransactionsLookup.Failed failed -> {
                recordInconclusive(refundCase, now);
                recordCheck(refundCase, failed.kind() == PaymentTransactionsLookup.FailureKind.ERROR_RESPONSE
                    ? CheckOutcome.PG_ERROR : CheckOutcome.PG_UNREACHABLE, failed.reason(), now);
            }
            case PaymentTransactionsLookup.Found result -> {
                List<PaymentTransaction> transactions = result.transactions();
                if (transactions.isEmpty()) {
                    recordInconclusive(refundCase, now);
                    recordCheck(refundCase, CheckOutcome.EMPTY, null, now);
                } else if (transactions.size() > 1 || !matches(refundCase, transactions.get(0))) {
                    applyAnomaly(refundCase, transactions.size() > 1 ? RefundCase.MULTIPLE_TRANSACTIONS
                        : RefundCase.TRANSACTION_MISMATCH, now);
                    recordCheck(refundCase, CheckOutcome.MISMATCH, null, now);
                } else {
                    cancelCommand = applyTransaction(refundCase, transactions.get(0), now, round);
                }
            }
        }
        paymentRefundCaseRepository.save(refundCase);
        return cancelCommand;
    }

    // 다른 거래·복수 거래는 돈이 나갔다는 근거라 확인 중이면 청구 확인으로 적는다. 자동 판단의 근거가 깨졌으므로 담당만 사람에게 넘긴다.
    private void applyAnomaly(RefundCase refundCase, String reason, Instant now) {
        if (refundCase.getMoneyStatus() == RefundCase.MoneyStatus.CHECKING) {
            refundCase.recordCharged(null);
        }
        if (refundCase.getOwner() == RefundCase.Owner.AUTO) {
            assignPerson(refundCase, reason, now);
            return;
        }
        recordInconclusive(refundCase, now);
    }

    // 환불 요청 뒤(REFUND_REQUESTED·REFUND_UNKNOWN)는 요청이 나갔을 수 있으므로 어떤 경우에도 다시 보내지 않고 조회만 한다.
    // 청구 확인·사람 담당에 FAILED가 오면 사실이 엇갈린 것이라 돈·담당을 바꾸지 않고 사람이 판단하게 둔다(마지막 확인은 MISMATCH).
    // 취소를 보내는 회차의 마지막 확인은 취소 결과를 반영할 때 남긴다(회차당 한 번).
    private PaymentCancelCommand applyTransaction(RefundCase refundCase, PaymentTransaction transaction, Instant now,
        Round round) {
        boolean checking = refundCase.getMoneyStatus() == RefundCase.MoneyStatus.CHECKING;
        CheckOutcome outcome = CheckOutcome.OK;
        switch (transaction.status()) {
            case SUCCESS -> {
                if (checking) {
                    refundCase.recordCharged(transaction.transactionKey());
                }
                if (refundCase.canRequestRefund(round.trigger())) {
                    refundCase.requestRefund(round.trigger(), now, jittered(RefundCase.FIRST_CHECK_DELAY));
                    return new PaymentCancelCommand(refundCase.getProviderOrderId(), refundCase.getTransactionKey(),
                        refundCase.getAmount());
                }
                recordInconclusive(refundCase, now);
            }
            case FAILED -> {
                if (checking) {
                    conclude(refundCase, Result.NO_CHARGE, now, round);
                } else {
                    recordInconclusive(refundCase, now);
                    outcome = CheckOutcome.MISMATCH;
                }
            }
            case CANCELED -> conclude(refundCase, Result.REFUNDED, now, round);
            case PENDING -> {
                recordInconclusive(refundCase, now);
                outcome = CheckOutcome.PENDING;
            }
        }
        recordCheck(refundCase, outcome, null, now);
        return null;
    }

    private void recordInconclusive(RefundCase refundCase, Instant now) {
        refundCase.recordInconclusiveCheck(now, jittered(refundCase.inconclusiveCheckCeiling()));
    }

    // 다음 조회 시각을 결제 확정 조회와 같은 full jitter로 흩뜨린다. 장애 뒤 만료가 몰려도 PG 복구 직후 한꺼번에 조회하지 않게 한다.
    private Duration jittered(Duration ceiling) {
        return Duration.ofMillis(lookupJitter.chooseMillis(ceiling.toMillis()));
    }

    private static boolean matches(RefundCase refundCase, PaymentTransaction transaction) {
        return refundCase.getProviderOrderId().equals(transaction.providerOrderId())
            && refundCase.getAmount().equals(transaction.amount())
            && (refundCase.getTransactionKey() == null || refundCase.getTransactionKey().equals(transaction.transactionKey()));
    }

    // NotSent도 결과 모름으로 둔다. 요청 전 기록이 이미 commit됐으므로 "한 번만" 원칙상 다시 보내지 않고 조회로만 확인한다(PL P11-1).
    // 거절의 PG 코드는 PG가 준 텍스트라 원인에 넣지 않는다. 결과 모름·미발송의 사유는 우리 게이트웨이가 만든 클래스 이름·상태 코드다(PL P4).
    private void applyCancelResult(long orderId, PaymentCancelResult result, Round round) {
        Optional<RefundCase> found = paymentRefundCaseRepository.findByOrderIdForUpdate(orderId);
        if (found.isEmpty() || found.get().getMoneyStatus() != RefundCase.MoneyStatus.REFUND_REQUESTED) {
            // 응답을 기다리는 사이 다른 인스턴스의 회차가 조회로 결론을 냈다. 사람이 볼 수 있게 결과 종류만 남긴다.
            log.warn("상태가 이미 바뀐 대상에 늦게 온 취소 결과를 반영하지 않습니다. orderId={}, moneyStatus={}, cancelResult={}",
                orderId, found.map(RefundCase::getMoneyStatus).orElse(null), result.getClass().getSimpleName());
            return;
        }
        RefundCase refundCase = found.get();
        Instant now = clock.instant();
        switch (result) {
            case PaymentCancelResult.Canceled canceled -> {
                conclude(refundCase, Result.REFUNDED, now, round);
                recordCheck(refundCase, CheckOutcome.OK, null, now);
            }
            case PaymentCancelResult.RejectedNoEffect rejected -> {
                boolean assigned = refundCase.getOwner() == RefundCase.Owner.AUTO;
                refundCase.markRefundRejected(now, jittered(RefundCase.PERSON_CHECK_DELAY));
                if (assigned) {
                    recordAssigned(refundCase.getOrderId(), RefundCase.REFUND_REJECTED);
                }
                recordCheck(refundCase, CheckOutcome.CANCEL_REJECTED, null, now);
            }
            case PaymentCancelResult.Unknown unknown -> {
                markUnknown(refundCase, result, now);
                recordCheck(refundCase, CheckOutcome.CANCEL_UNKNOWN, unknown.reason(), now);
            }
            case PaymentCancelResult.NotSent notSent -> {
                markUnknown(refundCase, result, now);
                recordCheck(refundCase, CheckOutcome.CANCEL_UNKNOWN, notSent.reason(), now);
            }
        }
        paymentRefundCaseRepository.save(refundCase);
    }

    private void markUnknown(RefundCase refundCase, PaymentCancelResult result, Instant now) {
        refundCase.markRefundUnknown(now, jittered(RefundCase.FIRST_CHECK_DELAY));
        log.warn("환불 결과를 알 수 없어 다시 보내지 않고 조회로 확인합니다. orderId={}, cancelResult={}", refundCase.getOrderId(),
            result.getClass().getSimpleName());
    }

    // 환불됨의 주체는 도메인이 우리가 본 경로로 정한다. 조회로 본 청구 없음은 시스템이 낸 결론이다.
    private void conclude(RefundCase refundCase, Result result, Instant now, Round round) {
        if (result == Result.REFUNDED) {
            refundCase.markRefunded(now, round.closedBy());
        } else {
            refundCase.closeNoCharge(now, round.closedBy());
        }
        RefundCase.RefundActor actor = result == Result.REFUNDED ? refundCase.getRefundActor() : RefundCase.RefundActor.SYSTEM;
        afterCommit(() -> refundOutcomeRecorder.recordOutcome(result, actor));
    }

    private void recordCheck(RefundCase refundCase, CheckOutcome outcome, String cause, Instant now) {
        refundCase.recordCheck(outcome, cause, now);
        afterCommit(() -> refundOutcomeRecorder.recordCheck(outcome));
    }

    private void assignPerson(RefundCase refundCase, String reason, Instant now) {
        refundCase.assignPerson(reason, now, jittered(RefundCase.PERSON_CHECK_DELAY));
        recordAssigned(refundCase.getOrderId(), reason);
    }

    private void recordAssigned(long orderId, String reason) {
        afterCommit(() -> {
            refundOutcomeRecorder.recordPersonAssigned(reason);
            log.warn("환불 확인 대상의 담당을 사람에게 넘겼습니다. orderId={}, reason={}", orderId, reason);
        });
    }

    private static void restoreOrderId(String previousOrderId) {
        if (previousOrderId == null) {
            MDC.remove(ORDER_ID_KEY);
            return;
        }
        MDC.put(ORDER_ID_KEY, previousOrderId);
    }

    // rollback된 결론을 세거나 알리지 않도록 commit 뒤에만 실행한다.
    private static void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("환불 결론 집계는 트랜잭션 안에서만 기록할 수 있습니다.");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    // 회차를 누가 돌렸는지와, 돈이 끝나 사람 담당을 닫을 때 남길 주체다.
    private record Round(CheckTrigger trigger, String closedBy) {
        static final Round SCHEDULED = new Round(CheckTrigger.SCHEDULER, RefundCase.CLOSED_BY_SYSTEM);
    }

    public record ProcessNowResult(long orderId, String moneyStatus, String refundActor, String owner, Instant ownerClosedAt,
        String ownerClosedBy, Instant lastCheckAt, String lastCheckOutcome, String lastCheckCause) { }
}
