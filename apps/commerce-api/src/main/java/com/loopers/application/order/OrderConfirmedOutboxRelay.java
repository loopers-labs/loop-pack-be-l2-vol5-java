package com.loopers.application.order;

import com.loopers.domain.order.OrderConfirmedOutbox;
import com.loopers.domain.order.OrderConfirmedOutboxRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Outbox 단일 relay (7주차 설계 D22~D31). 한 회차 = 조회 TX → 행마다 [시도 시작 TX → TX 밖 send 대기 → 결과 TX].
 * - 전송 대기 중에는 DB 트랜잭션·커넥션을 붙잡지 않는다. 각 TX 는 TransactionTemplate 으로 짧게 연다 (D23)
 * - 상태 변경은 이 클래스의 결과 TX 한 곳에서만 한다. 전송기는 결과 값만 돌려주고 콜백을 달지 않는다 (D25)
 * - broker 가 응답하지 않으면(RETRY) 그 행만 재시도 예약하고 회차를 일찍 끝낸다. 영구 오류(BLOCKED)는 그 행만 막고 계속 간다 (D24·D29)
 * - 같은 프로세스 안의 겹침은 tryLock 으로 막는다. 이것은 한 프로세스 안 보호일 뿐이며, 다중 인스턴스는 outbox.relay.enabled 설정으로만 막는다 (D22)
 * - 회차 결과를 값으로 돌려주고 같은 값으로 요약 로그 1줄을 남긴다 (D22·D31)
 */
@Slf4j
@Component
public class OrderConfirmedOutboxRelay {

    private final OrderConfirmedOutboxRepository repository;
    private final OutboxMessageSender sender;
    private final OutboxRelayProperties properties;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;
    private final ReentrantLock running = new ReentrantLock();

    public OrderConfirmedOutboxRelay(
        OrderConfirmedOutboxRepository repository,
        OutboxMessageSender sender,
        OutboxRelayProperties properties,
        Clock clock,
        PlatformTransactionManager transactionManager
    ) {
        this.repository = repository;
        this.sender = sender;
        this.properties = properties;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * 한 회차. 스케줄러·테스트·resume 이 부른다. 다른 회차가 실행 중이면 집지 않고 ALREADY_RUNNING 을 돌려준다.
     */
    public OutboxRelayResult runOnce() {
        if (!running.tryLock()) {
            log.warn("outbox relay 회차 건너뜀. reason=ALREADY_RUNNING (같은 프로세스에서 다른 회차 실행 중)");
            return OutboxRelayResult.alreadyRunning();
        }
        try {
            OutboxRelayResult result = runRound();
            log.info("outbox relay 회차 끝. reason={}, picked={}, published={}, retried={}, blocked={}, skippedStale={}, deferred={}, pendingRemaining={}",
                result.reason(), result.picked(), result.published(), result.retried(), result.blocked(), result.skippedStale(),
                result.deferred(), result.remainingPending());
            return result;
        } finally {
            running.unlock();
        }
    }

    /**
     * 재개 (D29). BLOCKED 행을 PENDING 으로 되돌린 뒤(nextAttemptAt = now) 한 회차를 돌린다.
     * 추측하지 않고 오류를 드러낸다: 없는 eventId 는 NOT_FOUND, 있지만 BLOCKED 가 아닌 행은 CONFLICT.
     */
    public OutboxRelayResult resume(String eventId) {
        ZonedDateTime now = now();
        int resumed = transactionTemplate.execute(status -> {
            OrderConfirmedOutbox outbox = repository.findByEventId(eventId)
                .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "[eventId = " + eventId + "] Outbox 행이 없습니다."));
            if (outbox.getStatus() != OrderConfirmedOutbox.Status.BLOCKED) {
                throw new CoreException(ErrorType.CONFLICT,
                    "[eventId = " + eventId + "] BLOCKED 가 아니라 재개할 수 없습니다. 현재 상태: " + outbox.getStatus());
            }
            return repository.resumeBlocked(eventId, now);
        });
        if (resumed != 1) {
            throw new CoreException(ErrorType.CONFLICT, "[eventId = " + eventId + "] 재개 중 상태가 바뀌어 되돌리지 못했습니다.");
        }
        log.info("outbox relay 재개. eventId={} BLOCKED → PENDING", eventId);
        return runOnce();
    }

    /** 저장 시각은 확정 경로와 같은 규칙으로 밀리초까지만 쓴다 (W6 D2). */
    private ZonedDateTime now() {
        return ZonedDateTime.now(clock).truncatedTo(ChronoUnit.MILLIS);
    }

    private OutboxRelayResult runRound() {
        ZonedDateTime now = now();
        // ① 조회 TX (읽기만). 재시도 시간이 된 PENDING 을 안정된 순서로 묶음 크기만큼
        List<OrderConfirmedOutbox> batch = transactionTemplate.execute(status ->
            repository.findPendingDue(now, properties.batchSize()));

        int published = 0;
        int retried = 0;
        int blocked = 0;
        int skippedStale = 0;
        int deferred = 0;
        OutboxRelayResult.Reason reason = OutboxRelayResult.Reason.COMPLETED;

        for (int i = 0; i < batch.size(); i++) {
            OrderConfirmedOutbox row = batch.get(i);
            // ② 시도 시작 TX. 0행이면 조회 뒤 상태가 바뀐 행이다. 단일 relay 에서는 나오면 안 되는 이상 신호라 로그로 드러내고 보내지 않는다
            int started = transactionTemplate.execute(status -> repository.startAttempt(row.getId()));
            if (started == 0) {
                skippedStale++;
                log.warn("outbox 시도 직전에 상태가 바뀌어 건너뜀 (단일 relay 에서는 비정상). eventId={}, orderId={}", row.getEventId(), row.getOrderId());
                continue;
            }
            int attemptCount = row.getAttemptCount() + 1;

            // ③ TX 밖. 저장된 key·payload 를 그대로 보내고 결과만 받는다
            OutboxSendResult result = sender.send(row.getMessageKey(), row.getPayload());

            // ④ 결과 TX. 모두 WHERE status = PENDING 조건부 UPDATE (D25)
            switch (result.outcome()) {
                case PUBLISHED -> {
                    ZonedDateTime publishedAt = now();
                    transactionTemplate.execute(status -> repository.markPublished(row.getId(), publishedAt));
                    published++;
                    log.info("outbox 발행 성공. eventId={}, orderId={}, attempt={}, topic={}, partition={}, offset={}",
                        row.getEventId(), row.getOrderId(), attemptCount, result.topic(), result.partition(), result.offset());
                }
                case BLOCKED -> {
                    transactionTemplate.execute(status -> repository.markBlocked(row.getId(), result.detail()));
                    blocked++;
                    log.error("outbox 영구 오류 → BLOCKED. 사람이 확인 뒤 resume 해야 한다. eventId={}, orderId={}, attempt={}, cause={}",
                        row.getEventId(), row.getOrderId(), attemptCount, result.detail());
                }
                case RETRY -> {
                    ZonedDateTime nextAttemptAt = now().plus(backoffAfter(attemptCount));
                    transactionTemplate.execute(status -> repository.scheduleRetry(row.getId(), nextAttemptAt, result.detail()));
                    retried++;
                    log.warn("outbox 전송 실패·미확인 → 재시도 예약. broker 불가로 이번 회차를 끝낸다. eventId={}, orderId={}, attempt={}, nextAttemptAt={}, cause={}",
                        row.getEventId(), row.getOrderId(), attemptCount, nextAttemptAt, result.detail());
                    reason = OutboxRelayResult.Reason.BROKER_UNAVAILABLE;
                }
            }
            if (reason == OutboxRelayResult.Reason.BROKER_UNAVAILABLE) {
                deferred = batch.size() - i - 1;
                break;
            }
        }

        long remaining = transactionTemplate.execute(status -> repository.countPending());
        return new OutboxRelayResult(reason, batch.size(), published, retried, blocked, skippedStale, deferred, remaining);
    }

    /**
     * n 번째 시도가 실패한 뒤 다음 시도까지 = min(initial-backoff × multiplier^(n−1), max-backoff). 곱셈은 상한에 닿으면 멈춘다 (D28).
     * 기존 PaymentFacade.intervalAfter 와 같은 규칙이다.
     */
    private Duration backoffAfter(int attemptCount) {
        Duration max = properties.maxBackoff();
        Duration backoff = properties.initialBackoff();
        for (int i = 1; i < attemptCount && backoff.compareTo(max) < 0; i++) {
            backoff = backoff.multipliedBy(properties.multiplier());
        }
        return backoff.compareTo(max) > 0 ? max : backoff;
    }
}
