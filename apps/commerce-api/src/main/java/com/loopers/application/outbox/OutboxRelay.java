package com.loopers.application.outbox;

import com.loopers.application.order.OrderConfirmedPublisher;
import com.loopers.application.order.SentRecord;
import com.loopers.domain.outbox.OutboxEventRepository;
import com.loopers.domain.outbox.OutboxStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Outbox 행을 읽어 broker가 받을 때까지 다시 보낸다(docs/week7/decisions.md D-2, D-4, D-15). 얻는 것은 누락 없는
 * at-least-once다 — 같은 eventId가 두 번 실릴 수 있고, 그건 consumer의 중복 처리(W6 D-18)가 받는다.
 *
 * <p>한 행은 짧은 TX-1(집기, 임대) → TX 밖에서 send·대기 → 짧은 TX-2(PUBLISHED 또는 실패 기록)다(D-2).
 * - TX는 {@link OutboxDeliveryLedger}(다른 빈)가 연다. 여기는 트랜잭션이 없다 — send를 기다리는 동안 DB 연결을 붙잡지 않는다.
 * - send에 더 짧은 대기 상한을 두지 않는다. producer가 정해진 시간 안에 반드시 성공·실패를 돌려준다. relay가 먼저 포기하면
 *   producer 안의 전송이 살아 있는 채로 임대가 풀린다(D-2).
 * - 임대 = producer 최대 전송 시간(max.block + delivery.timeout, 전송 포트가 실제 설정에서 계산) + 여유(INV-704).
 * - 한 회차는 보낼 차례인 PENDING을 id 순서로 최대 batch-size건 골라 한 건씩 차례로 처리한다. 병렬로 보내지 않는다(D-15).
 * - "이미 돌고 있으면 건너뜀" 장치를 스케줄 회차와 수동 재개가 함께 쓴다. 다른 서버와의 겹침은 조건부 UPDATE가 막는다(D-4).
 * - "지금"은 모두 주입한 Clock에서 얻는다(D-11).
 * - PUBLISHED 저장이 실패하면 기록만 하고 넘어간다. 행은 임대가 걸린 PENDING으로 남아, 임대가 끝난 뒤 같은 eventId로 다시
 *   나간다(at-least-once).
 */
@Slf4j
@Component
public class OutboxRelay {

    /** last_error에 남기는 예외 메시지 길이. 짧은 식별 정보면 충분하다. */
    private static final int MAX_MESSAGE_LENGTH = 300;

    private final OutboxEventRepository outboxEventRepository;
    private final OutboxDeliveryLedger ledger;
    private final OrderConfirmedPublisher publisher;
    private final Clock clock;
    private final int batchSize;
    private final Duration leaseMargin;
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Autowired
    public OutboxRelay(
        OutboxEventRepository outboxEventRepository,
        OutboxDeliveryLedger ledger,
        OrderConfirmedPublisher publisher,
        Clock clock,
        @Value("${outbox.relay.batch-size:10}") int batchSize,
        @Value("${outbox.relay.lease-margin:1m}") Duration leaseMargin
    ) {
        this.outboxEventRepository = outboxEventRepository;
        this.ledger = ledger;
        this.publisher = publisher;
        this.clock = clock;
        this.batchSize = batchSize;
        this.leaseMargin = leaseMargin;
    }

    /** 집을 때 next_attempt_at에 더하는 임대 시간 = producer 최대 전송 시간 + 여유(D-2, INV-704). */
    public Duration lease() {
        return publisher.maxSendTime().plus(leaseMargin);
    }

    /** 스케줄이 부르는 한 회차. 앞 회차(또는 수동 재개)가 돌고 있으면 건너뛴다. */
    public OutboxRelayRound runOnce() {
        if (!running.compareAndSet(false, true)) {
            log.info("Outbox relay 회차 건너뜀: 이전 회차가 아직 돌고 있다");
            return OutboxRelayRound.skippedRound();
        }
        try {
            return deliver(outboxEventRepository.findDueIds(now(), batchSize));
        } finally {
            running.set(false);
        }
    }

    /** 수동 재개가 부르는 한 회차. 같은 장치를 쓰고, 그 행 하나만 보낼 차례일 때 보낸다(D-15). */
    public OutboxRelayRound runOnceFor(String eventId) {
        if (!running.compareAndSet(false, true)) {
            log.info("Outbox relay 회차 건너뜀: 이전 회차가 아직 돌고 있다 (eventId={})", eventId);
            return OutboxRelayRound.skippedRound();
        }
        try {
            List<Long> ids = outboxEventRepository.findByEventId(eventId)
                .map(event -> List.of(event.getId()))
                .orElse(List.of());
            return deliver(ids);
        } finally {
            running.set(false);
        }
    }

    private OutboxRelayRound deliver(List<Long> ids) {
        Duration lease = lease();
        int picked = 0;
        int sent = 0;
        int failed = 0;
        int blocked = 0;
        for (Long id : ids) {
            if (Thread.currentThread().isInterrupted()) {
                log.warn("Outbox relay 회차 중단: 스레드가 인터럽트되었다");
                break;
            }
            Outcome outcome = deliverOne(id, lease);
            if (outcome != Outcome.NOT_CLAIMED) {
                picked++;
            }
            if (outcome == Outcome.SENT) {
                sent++;
            } else if (outcome == Outcome.FAILED) {
                failed++;
            } else if (outcome == Outcome.BLOCKED) {
                failed++;
                blocked++;
            }
        }
        OutboxRelayRound round = new OutboxRelayRound(false, ids.size(), picked, sent, failed, blocked);
        logRound(round);
        return round;
    }

    private enum Outcome { NOT_CLAIMED, SENT, FAILED, BLOCKED }

    private Outcome deliverOne(Long id, Duration lease) {
        ZonedDateTime claimedAt = now();
        Optional<ClaimedOutbox> claimed = ledger.claim(id, claimedAt, claimedAt.plus(lease));
        if (claimed.isEmpty()) {
            return Outcome.NOT_CLAIMED;
        }
        ClaimedOutbox row = claimed.get();

        SentRecord sentRecord;
        try {
            sentRecord = publisher.send(row.messageKey(), row.payload());
        } catch (RuntimeException e) {
            return recordFailure(row, describe(e));
        }

        try {
            ledger.markPublished(row.id(), sentRecord, now());
        } catch (RuntimeException e) {
            // broker는 이미 받았다. 행은 임대가 걸린 PENDING으로 남고, 임대가 끝나면 같은 eventId로 다시 나간다(at-least-once).
            log.error("Outbox PUBLISHED 저장 실패 — 임대 뒤 다시 보낸다: eventId={}, orderId={}, offset={}, error={}",
                row.eventId(), row.orderId(), sentRecord.offset(), describe(e));
        }
        return Outcome.SENT;
    }

    private Outcome recordFailure(ClaimedOutbox row, String error) {
        try {
            OutboxDeliveryLedger.FailureOutcome outcome = ledger.recordFailure(row.id(), error, now());
            if (outcome == OutboxDeliveryLedger.FailureOutcome.BLOCKED) {
                log.warn("Outbox BLOCKED: eventId={}, orderId={}, attempt={}, createdAt={}, lastError={}",
                    row.eventId(), row.orderId(), row.attemptCount(), row.createdAt(), error);
                return Outcome.BLOCKED;
            }
            log.info("Outbox 전송 실패 — 백오프 뒤 다시: eventId={}, orderId={}, attempt={}, error={}",
                row.eventId(), row.orderId(), row.attemptCount(), error);
        } catch (RuntimeException e) {
            // 실패 기록마저 실패하면 행은 임대가 걸린 PENDING으로 남는다. 임대가 끝나면 다시 집힌다.
            log.error("Outbox 실패 기록 실패 — 임대 뒤 다시 집힌다: eventId={}, orderId={}, sendError={}, error={}",
                row.eventId(), row.orderId(), error, describe(e));
        }
        return Outcome.FAILED;
    }

    /** 회차 끝 구조화 로그 한 줄. 6주차 미발행 Counter를 대신해 전송 수·PENDING 수·가장 오래 기다린 시각을 남긴다(D-9). */
    private void logRound(OutboxRelayRound round) {
        long pendingCount = outboxEventRepository.countByStatus(OutboxStatus.PENDING);
        long blockedCount = outboxEventRepository.countByStatus(OutboxStatus.BLOCKED);
        String oldestPendingCreatedAt = outboxEventRepository.findOldestCreatedAt(OutboxStatus.PENDING)
            .map(time -> time.toInstant().toString())
            .orElse("-");
        String line = "outbox.relay.round due=" + round.due() + " picked=" + round.picked() + " sent=" + round.sent()
            + " failed=" + round.failed() + " blocked=" + round.blocked() + " pendingCount=" + pendingCount
            + " oldestPendingCreatedAt=" + oldestPendingCreatedAt + " blockedCount=" + blockedCount;
        // 보낼 차례가 없던 회차까지 INFO로 남기면 짧은 회차 간격(1초)에서 로그가 넘친다.
        if (round.due() > 0) {
            log.info(line);
        } else {
            log.debug(line);
        }
    }

    private ZonedDateTime now() {
        return ZonedDateTime.now(clock);
    }

    /** 예외 종류와 짧은 메시지만 남긴다. payload는 전송 예외 메시지에 들어가지 않는다(OrderConfirmedSendException). */
    private static String describe(RuntimeException e) {
        String message = e.getMessage();
        if (message == null) {
            return e.getClass().getSimpleName();
        }
        if (message.length() > MAX_MESSAGE_LENGTH) {
            message = message.substring(0, MAX_MESSAGE_LENGTH);
        }
        return e.getClass().getSimpleName() + ": " + message;
    }
}
