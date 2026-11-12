package com.loopers.coupon.application;

import com.loopers.coupon.application.port.in.CouponIssueRelayUseCase;
import com.loopers.coupon.application.port.out.CouponIssueOutboxPort;
import com.loopers.coupon.domain.CouponIssueOutbox;
import com.loopers.coupon.domain.CouponIssueOutboxStatus;
import com.loopers.outbox.application.port.out.MessageSendPort;
import com.loopers.outbox.application.port.out.MessageSendPort.SentPosition;
import com.loopers.outbox.domain.OrderOutboxBackoff;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * 당첨 기록 relay 한 회차 (D-2, W7 OrderOutboxRelayService와 같은 방식). W7 relay는 주문 전용이라 따로 둔다 (D-5).
 * <ol>
 *   <li>차례가 된 행을 id 순서로 최대 batch-size개 고른다</li>
 *   <li>보내기: 한 행씩 [TX1] SENDING(+lease) → [TX 밖] send. ack를 기다리지 않고 다음 행으로 간다.
 *       send() 호출 자체가 실패하면 그 행을 실패로 기록하고 회차를 멈춘다</li>
 *   <li>기다리기: 보낸 행의 broker 응답을 id 순서로 기다리며 행마다 [TX2] 자기 결과를 기록한다</li>
 * </ol>
 * PUBLISHED는 broker 응답(ack)을 받은 뒤에만 기록한다. 같은 기록이 두 번 나가도 consumer가 UNIQUE로 거른다 (CPN-08).
 * MessageSendPort·backoff는 W7 것을 그대로 쓴다.
 */
@Slf4j
@Service
public class CouponIssueRelayService implements CouponIssueRelayUseCase {

    private final CouponIssueOutboxPort outboxPort;
    private final MessageSendPort messageSendPort;
    private final OrderOutboxBackoff backoff;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final String topic;
    private final int batchSize;
    private final Duration sendWait;

    public CouponIssueRelayService(
        CouponIssueOutboxPort outboxPort,
        MessageSendPort messageSendPort,
        OrderOutboxBackoff backoff,
        Clock clock,
        PlatformTransactionManager transactionManager,
        @Value("${kafka.topics.coupon-issue}") String topic,
        @Value("${coupon.outbox.relay.batch-size}") int batchSize,
        @Value("${coupon.outbox.relay.send-wait}") Duration sendWait
    ) {
        this.outboxPort = outboxPort;
        this.messageSendPort = messageSendPort;
        this.backoff = backoff;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
        this.topic = topic;
        this.batchSize = batchSize;
        this.sendWait = sendWait;
    }

    @Override
    public RelayResult relayOnce() {
        List<CouponIssueOutbox> due = outboxPort.findDue(now(), batchSize);
        List<InFlight> inFlight = new ArrayList<>();
        Long sendStoppedAt = null;
        for (CouponIssueOutbox row : due) {
            InFlight sent = send(row.getId());
            if (sent == null) {
                sendStoppedAt = row.getId();
                break;
            }
            inFlight.add(sent);
        }

        long deadline = System.nanoTime() + sendWait.toNanos();
        int published = 0;
        Long firstUnpublished = null;
        for (InFlight sent : inFlight) {
            if (awaitAndRecord(sent, deadline)) {
                published++;
            } else if (firstUnpublished == null) {
                firstUnpublished = sent.id();
            }
        }
        return new RelayResult(due.size(), published, firstUnpublished != null ? firstUnpublished : sendStoppedAt);
    }

    private record InFlight(Long id, CompletableFuture<SentPosition> ack) {}

    private InFlight send(Long id) {
        CouponIssueOutbox claimed;
        try {
            claimed = inTransaction(id, outbox -> outbox.startSending(now()));
        } catch (RuntimeException e) {
            log.error("relay가 당첨 기록을 맡지 못했습니다 — 회차를 멈춥니다. outboxId={}", id, e);
            return null;
        }
        try {
            return new InFlight(id, messageSendPort.send(topic, claimed.getMessageKey(), claimed.getPayload()));
        } catch (RuntimeException e) {
            recordFailure(id, e);
            return null;
        }
    }

    private boolean awaitAndRecord(InFlight sent, long deadline) {
        try {
            long remaining = Math.max(0L, deadline - System.nanoTime());
            sent.ack().get(remaining, TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException e) {
            recordFailure(sent.id(), e.getCause());
            return false;
        } catch (TimeoutException e) {
            recordFailure(sent.id(), e);
            return false;
        }
        try {
            inTransaction(sent.id(), outbox -> outbox.markPublished(now()));
            return true;
        } catch (RuntimeException e) {
            log.error("broker는 받았지만 PUBLISHED를 저장하지 못했습니다 — lease 뒤 다시 보냅니다(consumer가 중복을 거름). outboxId={}", sent.id(), e);
            return false;
        }
    }

    private void recordFailure(Long id, Throwable cause) {
        String error = cause == null ? "unknown" : cause.getClass().getSimpleName() + ": " + cause.getMessage();
        try {
            CouponIssueOutbox after = inTransaction(id,
                outbox -> outbox.recordFailure(error, now(), backoff.delayAfter(outbox.getAttemptCount() + 1)));
            if (after.getStatus() == CouponIssueOutboxStatus.BLOCKED) {
                log.error("[COUPON_OUTBOX_BLOCKED] {}번 실패해 자동 재시도를 멈췄습니다. outboxId={}, cause={}", after.getAttemptCount(), id, error);
            } else {
                log.warn("당첨 기록 send 실패 — {}에 다시 보냅니다. outboxId={}, cause={}", after.getNextAttemptAt(), id, error);
            }
        } catch (RuntimeException e) {
            log.error("send 실패를 저장하지 못했습니다 — lease 뒤 다시 보냅니다. outboxId={}, cause={}", id, error, e);
        }
    }

    private CouponIssueOutbox inTransaction(Long id, Consumer<CouponIssueOutbox> transition) {
        return transaction.execute(status -> {
            CouponIssueOutbox outbox = outboxPort.findById(id)
                .orElseThrow(() -> new IllegalStateException("당첨 기록이 없습니다: " + id));
            transition.accept(outbox);
            return outboxPort.save(outbox);
        });
    }

    private ZonedDateTime now() {
        return ZonedDateTime.now(clock);
    }
}
