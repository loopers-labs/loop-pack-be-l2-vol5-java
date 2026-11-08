package com.loopers.application.order;

import com.loopers.application.order.OrderOutboxExecutionGate.Result;
import com.loopers.application.order.OrderOutboxRelayException.Stage;
import com.loopers.application.order.OrderOutboxRelayTransactionService.BeginResult;
import com.loopers.application.order.OrderOutboxRelayTransactionService.StateChange;
import com.loopers.config.OrderOutboxClockConfig;
import com.loopers.config.OrderOutboxRelayProperties;
import com.loopers.domain.order.OrderConfirmed;
import com.loopers.domain.order.OrderOutboxAttempt;
import com.loopers.domain.order.OrderOutboxMessageException;
import com.loopers.domain.order.OrderOutboxMessageVerifier;
import com.loopers.domain.order.OrderOutboxReceipt;
import com.loopers.domain.order.OrderOutboxSender;
import com.loopers.domain.order.OrderOutboxStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * 단일 polling relay 의 수동 실행(설계 W7.3-4). TX 없이 공유 gate 안에서 행을 직렬로 처리한다.
 *
 * <p>행마다 짧은 시작 TX(시도 commit) → 저장 원문 검증 → TX 밖 send·유한 대기 → 별도 결과 TX 순서다. 외부 대기 중에는 DB TX·행 잠금·Entity 를
 * 들고 있지 않다. 늦게 끝난 Future 는 DB 상태를 바꾸지 않는다. 전송 실패만 행에 저장하고, DB·저장 정합성·전송 계약 오류는
 * {@link OrderOutboxRelayException} 으로 회차를 멈춘다. probe 예외는 그대로 전파한다. 종료 요청·interrupt 는 새 행·send·상태 저장을
 * 시작하지 않고 회차를 끝내며 성공·실패를 합성하지 않는다. 전송·본문 오류의 결과 TX 직전에도 같은 검사를 한다(종료 검사와 TX 진입 사이의
 * 모든 경쟁을 원자적으로 막지는 않는다). 로그에는 검증된 eventId·orderId·row PK·횟수·단계·안전 code 만 남긴다.
 */
@Slf4j
@Component
public class OrderOutboxRelay {

    public enum RowOutcome {
        PUBLISHED,
        RETRY_SCHEDULED,
        BLOCKED,
        NOT_DUE,
        SKIPPED_PUBLISHED,
        SKIPPED_BLOCKED,
        NOT_FOUND,
        /** 결과 TX 가 다른 시도 번호를 보고 변경하지 않았다. */
        STALE_ATTEMPT,
        /** 종료 요청·interrupt 로 이 행에서 멈췄다. 이 행의 결과는 저장하지 않았다. */
        INTERRUPTED
    }

    public record RowResult(long outboxId, RowOutcome outcome) {
    }

    static final String SEND_CALL_FAILED = "SEND_CALL_FAILED";
    static final String SEND_COMPLETION_FAILED = "SEND_COMPLETION_FAILED";
    static final String SEND_WAIT_TIMEOUT = "SEND_WAIT_TIMEOUT";

    private static final String EVENT_ID_PREFIX = "order-confirmed:";
    private static final Pattern CANONICAL_ID = Pattern.compile("[1-9][0-9]{0,18}");

    private final OrderOutboxRelayTransactionService transactions;
    private final OrderOutboxMessageVerifier verifier;
    private final OrderOutboxSender sender;
    private final OrderOutboxRelayProbe probe;
    private final OrderOutboxExecutionGate gate;
    private final Clock clock;
    private final int batchSize;
    private final Duration sendWaitTimeout;

    public OrderOutboxRelay(OrderOutboxRelayTransactionService transactions, OrderOutboxMessageVerifier verifier,
                            OrderOutboxSender sender, OrderOutboxRelayProbe probe, OrderOutboxExecutionGate gate,
                            @Qualifier(OrderOutboxClockConfig.OUTBOX_RECORD_CLOCK) Clock clock,
                            OrderOutboxRelayProperties properties) {
        this.transactions = transactions;
        this.verifier = verifier;
        this.sender = sender;
        this.probe = probe;
        this.gate = gate;
        this.clock = clock;
        this.batchSize = properties.batchSize();
        this.sendWaitTimeout = properties.sendWaitTimeout();
    }

    /** due 행을 nextAttemptAt→id 순으로 최대 batchSize 개 직렬 처리한다. 결과는 처리한 순서의 불변 목록이다. */
    public Result<List<RowResult>> runDueBatch() {
        requireNoTransaction();
        return gate.tryExecute(() -> {
            List<Long> ids = call(Stage.FIND_TARGET, null, null, () -> transactions.findDueIds(now(clock), batchSize));
            List<RowResult> results = new ArrayList<>(ids.size());
            for (Long id : ids) {
                RowResult result = process(id);
                results.add(result);
                if (result.outcome() == RowOutcome.INTERRUPTED) {
                    break;
                }
            }
            return List.copyOf(results);
        });
    }

    /** 명시 eventId 하나를 일반 시도와 같은 due·상태·예산 검사로 처리한다. BLOCKED 해제·PUBLISHED 재전송은 하지 않는다. */
    public Result<RowResult> runEvent(String eventId) {
        requireNoTransaction();
        requireCanonicalEventId(eventId);
        return gate.tryExecute(() -> {
            Long id = call(Stage.FIND_TARGET, null, null, () -> transactions.findIdByEventId(eventId))
                .orElseThrow(() -> new IllegalArgumentException("Outbox 행이 없는 eventId 입니다."));
            return process(id);
        });
    }

    private RowResult process(long id) {
        if (halted()) {
            return new RowResult(id, RowOutcome.INTERRUPTED);
        }
        BeginResult begin = call(Stage.BEGIN_ATTEMPT, id, null, () -> transactions.beginAttempt(id, now(clock)));
        switch (begin.outcome()) {
            case NOT_FOUND:
                return new RowResult(id, RowOutcome.NOT_FOUND);
            case NOT_DUE:
                return new RowResult(id, RowOutcome.NOT_DUE);
            case ALREADY_PUBLISHED:
                return new RowResult(id, RowOutcome.SKIPPED_PUBLISHED);
            case BLOCKED:
                return new RowResult(id, RowOutcome.SKIPPED_BLOCKED);
            case LIMIT_BLOCKED:
                log.warn("ORDER_OUTBOX_BLOCKED outboxId={} code={} eventId=UNVERIFIED",
                    id, OrderOutboxRelayTransactionService.UNCONFIRMED_ATTEMPT_LIMIT);
                return new RowResult(id, RowOutcome.BLOCKED);
            default:
                break;
        }
        OrderOutboxAttempt attempt = begin.attempt().orElseThrow();
        OrderConfirmed event;
        try {
            event = verifier.parseAndValidate(attempt.message());
        } catch (OrderOutboxMessageException invalid) {
            if (halted()) {
                // 종료를 관측했으면 새 결과 TX 를 시작하지 않는다. 시작 TX 의 commit 은 그대로 둔다.
                log.warn("ORDER_OUTBOX_RESULT_NOT_STORED outboxId={} attemptNo={} code={} eventId=INVALID",
                    id, attempt.attemptNo(), invalid.code());
                return new RowResult(id, RowOutcome.INTERRUPTED);
            }
            StateChange change = call(Stage.BLOCK_INVALID, id, attempt.attemptNo(),
                () -> transactions.blockInvalid(id, attempt.attemptNo(), invalid.code().name()));
            log.warn("ORDER_OUTBOX_BLOCKED outboxId={} attemptNo={} cycleAttemptCount={} code={} eventId=INVALID transition={}",
                id, attempt.attemptNo(), attempt.cycleAttemptCount(), invalid.code(), change.transition());
            return new RowResult(id, toOutcome(change, RowOutcome.BLOCKED));
        }
        if (halted()) {
            return new RowResult(id, RowOutcome.INTERRUPTED);
        }

        OrderOutboxReceipt receipt;
        try {
            CompletionStage<OrderOutboxReceipt> sending;
            try {
                sending = sender.send(attempt);
            } catch (RuntimeException callFailure) {
                return fail(attempt, event, SEND_CALL_FAILED, callFailure);
            }
            receipt = sending.toCompletableFuture().get(sendWaitTimeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            log.warn("ORDER_OUTBOX_SEND_INTERRUPTED outboxId={} eventId={} attemptNo={}", id, event.eventId(), attempt.attemptNo());
            return new RowResult(id, RowOutcome.INTERRUPTED);
        } catch (ExecutionException failed) {
            return fail(attempt, event, SEND_COMPLETION_FAILED, failed.getCause());
        } catch (CancellationException cancelled) {
            return fail(attempt, event, SEND_COMPLETION_FAILED, cancelled);
        } catch (TimeoutException timeout) {
            return fail(attempt, event, SEND_WAIT_TIMEOUT, null);
        }
        if (receipt == null) {
            throw failure(Stage.RECEIPT_CONTRACT, id, attempt.attemptNo(), new IllegalStateException("receipt 없음"));
        }

        probe.afterBrokerConfirmed(attempt, receipt);
        if (halted()) {
            log.warn("ORDER_OUTBOX_PUBLISH_NOT_STORED outboxId={} eventId={} attemptNo={} topic={} partition={} offset={}",
                id, event.eventId(), attempt.attemptNo(), receipt.topic(), receipt.partition(), receipt.offset());
            return new RowResult(id, RowOutcome.INTERRUPTED);
        }
        StateChange change = call(Stage.MARK_PUBLISHED, id, attempt.attemptNo(),
            () -> transactions.markPublished(id, attempt.attemptNo(), now(clock)));
        log.info("ORDER_OUTBOX_PUBLISHED outboxId={} eventId={} orderId={} attemptNo={} cycleAttemptCount={} topic={} partition={}"
                + " offset={} transition={}", id, event.eventId(), event.orderId(), attempt.attemptNo(), attempt.cycleAttemptCount(),
            receipt.topic(), receipt.partition(), receipt.offset(), change.transition());
        return new RowResult(id, toOutcome(change, RowOutcome.PUBLISHED));
    }

    /** 전송 실패를 안전 code 와 원인 클래스명으로 저장한다. 원인 message·stacktrace 는 저장·출력하지 않는다. */
    private RowResult fail(OrderOutboxAttempt attempt, OrderConfirmed event, String code, Throwable cause) {
        String safeError = cause == null ? code : code + ":" + cause.getClass().getName();
        long id = attempt.outboxId();
        if (halted()) {
            // 종료를 관측했으면 실패 code·다음 시각·차단을 새로 저장하지 않는다. 시작 TX 의 commit 은 그대로 둔다.
            log.warn("ORDER_OUTBOX_RESULT_NOT_STORED outboxId={} eventId={} attemptNo={} error={}",
                id, event.eventId(), attempt.attemptNo(), safeError);
            return new RowResult(id, RowOutcome.INTERRUPTED);
        }
        StateChange change = call(Stage.RECORD_FAILURE, id, attempt.attemptNo(),
            () -> transactions.recordFailure(id, attempt.attemptNo(), safeError, now(clock)));
        log.warn("ORDER_OUTBOX_SEND_FAILED outboxId={} eventId={} attemptNo={} cycleAttemptCount={} error={} status={} transition={}",
            id, event.eventId(), attempt.attemptNo(), attempt.cycleAttemptCount(), safeError, change.status(), change.transition());
        RowOutcome applied = change.status() == OrderOutboxStatus.BLOCKED ? RowOutcome.BLOCKED : RowOutcome.RETRY_SCHEDULED;
        return new RowResult(id, toOutcome(change, applied));
    }

    private static RowOutcome toOutcome(StateChange change, RowOutcome applied) {
        return switch (change.transition()) {
            case APPLIED -> applied;
            case ALREADY_PUBLISHED -> RowOutcome.SKIPPED_PUBLISHED;
            case STALE_ATTEMPT, ALREADY_PENDING -> RowOutcome.STALE_ATTEMPT;
        };
    }

    /** DB·정합성 오류를 단계와 함께 감싸 회차를 멈춘다. Error 는 잡지 않는다. */
    private static <T> T call(Stage stage, Long id, Long attemptNo, Supplier<T> action) {
        try {
            return action.get();
        } catch (RuntimeException dbFailure) {
            throw failure(stage, id, attemptNo, dbFailure);
        }
    }

    private static OrderOutboxRelayException failure(Stage stage, Long id, Long attemptNo, RuntimeException cause) {
        OrderOutboxRelayException failure = new OrderOutboxRelayException(stage, id, attemptNo, cause);
        log.error("ORDER_OUTBOX_RELAY_STOPPED {}", failure.getMessage());
        return failure;
    }

    private boolean halted() {
        return gate.isStopping() || Thread.currentThread().isInterrupted();
    }

    static void requireNoTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Outbox relay·운영 진입점은 상위 TX 안에서 실행할 수 없습니다.");
        }
    }

    /** 기존 유도 규칙의 정규 eventId(order-confirmed:{양수 long})인지 본다. 오류 문구에 입력을 넣지 않는다. */
    static void requireCanonicalEventId(String eventId) {
        boolean valid = eventId != null && eventId.startsWith(EVENT_ID_PREFIX);
        if (valid) {
            String id = eventId.substring(EVENT_ID_PREFIX.length());
            valid = CANONICAL_ID.matcher(id).matches();
            if (valid) {
                try {
                    Long.parseLong(id);
                } catch (NumberFormatException overflow) {
                    valid = false;
                }
            }
        }
        if (!valid) {
            throw new IllegalArgumentException("eventId 는 order-confirmed:{양수 주문 ID} 정규 형식이어야 합니다.");
        }
    }

    /** 운영 시각. 기존 outboxRecordClock 을 UTC·마이크로초로 정규화한다. 사건 confirmedAt 과 다르다. */
    static ZonedDateTime now(Clock clock) {
        return ZonedDateTime.now(clock).withZoneSameInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }
}
