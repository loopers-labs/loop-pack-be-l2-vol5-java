package com.loopers.application.order;

import com.loopers.application.order.OrderOutboxExecutionGate.Result;
import com.loopers.application.order.OrderOutboxRelay.RowResult;
import com.loopers.config.OrderOutboxClockConfig;
import com.loopers.domain.order.OrderConfirmedOutboxModel.Transition;
import com.loopers.domain.order.OrderOutboxMessageException;
import com.loopers.domain.order.OrderOutboxStatus;
import com.loopers.support.error.CoreException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;

/**
 * 유지된 context 에서 호출하는 내부 운영 진입점(설계 W7.3-6·W7.3-7). 공개·로컬 HTTP·CLI 는 없다. TX 없이 relay 와 같은 gate 를 쓴다.
 * 명시 실행 위임·BLOCKED 재개·명시 주문 복구를 제공한다. 전체 자동 스캔·과거 POINT 소급은 없다.
 */
@Slf4j
@Component
public class OrderOutboxOperations {

    static final String RECOVERY_DB_FAILURE = "RECOVERY_DB_FAILURE";
    static final String RECOVERY_INTERRUPTED = "RECOVERY_INTERRUPTED";
    static final String RECOVERY_FAILED = "RECOVERY_FAILED";

    public enum RecoveryOutcome {
        /** 없던 Outbox 행을 PENDING 으로 넣었다. */
        RESTORED,
        /** 같은 사실의 행이 이미 있어 아무것도 바꾸지 않았다. */
        EXISTS
    }

    /** 한 대상의 commit 된 복구 결과. RESTORED 는 PENDING, EXISTS 는 실제 기존 상태다. */
    public record RecoveryItem(long orderId, String eventId, RecoveryOutcome outcome, OrderOutboxStatus status) {

        public RecoveryItem {
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(status, "status");
            if (orderId < 1L || !("order-confirmed:" + orderId).equals(eventId)
                || (outcome == RecoveryOutcome.RESTORED && status != OrderOutboxStatus.PENDING)) {
                throw new IllegalArgumentException("복구 결과의 주문·사건·상태가 맞지 않습니다.");
            }
        }
    }

    /** 처리 순서의 불변 결과 목록. 정상 반환은 모든 명시 대상의 TX 가 끝난 뒤다. */
    public record RecoveryReport(List<RecoveryItem> items) {

        public RecoveryReport {
            items = List.copyOf(items);
        }
    }

    /**
     * 한 대상의 실패로 멈춘 복구. completed 는 commit 반환이 확인된 대상, failedOrderId 는 호출하다 실패한 대상(종료 관측이면 없음),
     * unattempted 는 오름차순 미시도 대상이다. 메시지에는 안전 code·건수만 담고 원인 message 를 넣지 않는다. 원인은 내부 진단용이다.
     */
    public static class RecoveryFailedException extends RuntimeException {

        private final List<RecoveryItem> completed;
        private final Optional<Long> failedOrderId;
        private final String failureCode;
        private final List<Long> unattempted;

        public RecoveryFailedException(List<RecoveryItem> completed, Optional<Long> failedOrderId, String failureCode,
                                       List<Long> unattempted, Throwable cause) {
            super("Outbox 복구 중단. [failureCode=" + failureCode + ", completed=" + completed.size() + ", failedOrderId="
                + failedOrderId.map(String::valueOf).orElse("none") + ", unattempted=" + unattempted.size() + "]", cause);
            this.completed = List.copyOf(completed);
            this.failedOrderId = Objects.requireNonNull(failedOrderId, "failedOrderId");
            this.failureCode = Objects.requireNonNull(failureCode, "failureCode");
            this.unattempted = List.copyOf(unattempted);
        }

        public List<RecoveryItem> completed() {
            return completed;
        }

        public Optional<Long> failedOrderId() {
            return failedOrderId;
        }

        public String failureCode() {
            return failureCode;
        }

        public List<Long> unattempted() {
            return unattempted;
        }
    }

    private final OrderOutboxRelay relay;
    private final OrderOutboxRecoveryTransactionService recoveryTransactions;
    private final OrderOutboxExecutionGate gate;
    private final Clock clock;

    public OrderOutboxOperations(OrderOutboxRelay relay, OrderOutboxRecoveryTransactionService recoveryTransactions,
                                 OrderOutboxExecutionGate gate, @Qualifier(OrderOutboxClockConfig.OUTBOX_RECORD_CLOCK) Clock clock) {
        this.relay = relay;
        this.recoveryTransactions = recoveryTransactions;
        this.gate = gate;
        this.clock = clock;
    }

    /** relay 에 그대로 위임한다. gate 는 relay 가 한 번 잡는다. */
    public Result<RowResult> runEvent(String eventId) {
        return relay.runEvent(eventId);
    }

    /** BLOCKED 를 PENDING·회차 0·nextAttemptAt=now 로 재개한다. 자동 send 는 없고 다음 relay 실행이 처리한다. */
    public Result<Transition> resumeBlocked(String eventId) {
        OrderOutboxRelay.requireNoTransaction();
        OrderOutboxRelay.requireCanonicalEventId(eventId);
        Result<Transition> result = gate.tryExecute(() -> recoveryTransactions.resumeBlocked(eventId, OrderOutboxRelay.now(clock)));
        log.info("ORDER_OUTBOX_RESUME eventId={} outcome={} transition={}", eventId, result.outcome(),
            result.value().map(Enum::name).orElse("none"));
        return result;
    }

    /**
     * 명시한 주문들을 중복 제거·오름차순으로 주문별 TX 에서 복구한다. 입력 오류는 gate 전에 거절하고, gate 는 한 번만 잡는다.
     * 각 대상 TX 전에 종료·interrupt 를 확인한다. 한 대상이 실패하면 이미 commit 된 대상은 그대로 두고 RecoveryFailedException 을 던진다.
     */
    public Result<RecoveryReport> recoverOrders(List<Long> orderIds) {
        OrderOutboxRelay.requireNoTransaction();
        if (orderIds == null || orderIds.isEmpty() || orderIds.stream().anyMatch(id -> id == null || id < 1L)) {
            throw new IllegalArgumentException("복구 대상은 비어 있지 않은 양수 주문 ID 목록이어야 합니다.");
        }
        List<Long> targets = List.copyOf(new TreeSet<>(orderIds));
        Result<RecoveryReport> result = gate.tryExecute(() -> recover(targets));
        log.info("ORDER_OUTBOX_RECOVERY outcome={} targets={} items={}", result.outcome(), targets.size(),
            result.value().map(report -> report.items().size()).orElse(0));
        return result;
    }

    private RecoveryReport recover(List<Long> targets) {
        List<RecoveryItem> completed = new ArrayList<>();
        for (int index = 0; index < targets.size(); index++) {
            Long orderId = targets.get(index);
            if (gate.isStopping() || Thread.currentThread().isInterrupted()) {
                throw failed(completed, Optional.empty(), RECOVERY_INTERRUPTED, targets.subList(index, targets.size()), null);
            }
            RecoveryItem item;
            try {
                item = recoveryTransactions.recoverOne(orderId);
            } catch (RuntimeException failure) {
                throw failed(completed, Optional.of(orderId), failureCode(failure), targets.subList(index + 1, targets.size()), failure);
            }
            completed.add(item);
            log.info("ORDER_OUTBOX_RECOVERED orderId={} eventId={} outcome={} status={}", item.orderId(), item.eventId(),
                item.outcome(), item.status());
        }
        return new RecoveryReport(completed);
    }

    private static RecoveryFailedException failed(List<RecoveryItem> completed, Optional<Long> failedOrderId, String code,
                                                  List<Long> unattempted, Throwable cause) {
        RecoveryFailedException failure = new RecoveryFailedException(completed, failedOrderId, code, unattempted, cause);
        log.warn("ORDER_OUTBOX_RECOVERY_FAILED {} causeType={}", failure.getMessage(), cause == null ? "none" : cause.getClass().getName());
        return failure;
    }

    /** 알려진 메시지 오류 code·CoreException 의 ErrorType 이름을 재사용하고, DB·interrupt·그 밖을 구분한다. interrupt 원인은 플래그를 복원한다. */
    private static String failureCode(RuntimeException failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                return RECOVERY_INTERRUPTED;
            }
        }
        if (failure instanceof OrderOutboxMessageException message) {
            return message.code().name();
        }
        if (failure instanceof CoreException core) {
            return core.getErrorType().name();
        }
        if (failure instanceof DataAccessException || failure instanceof TransactionException) {
            return RECOVERY_DB_FAILURE;
        }
        return RECOVERY_FAILED;
    }
}
