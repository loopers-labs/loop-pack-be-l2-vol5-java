package com.loopers.outbox.application;

import com.loopers.outbox.application.port.in.OrderOutboxRelayUseCase;
import com.loopers.outbox.application.port.out.MessageSendPort;
import com.loopers.outbox.application.port.out.MessageSendPort.SentPosition;
import com.loopers.outbox.application.port.out.OrderOutboxPort;
import com.loopers.outbox.domain.OrderOutbox;
import com.loopers.outbox.domain.OrderOutboxBackoff;
import com.loopers.outbox.domain.OrderOutboxStatus;
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
 * 단일 relay의 한 회차 (docs/week7/plan.md D-1).
 * <ol>
 *   <li>차례가 된 행을 id 순서로 최대 batch-size개 고른다</li>
 *   <li>보내기: id 순서로 한 행씩 [TX1] SENDING(+lease) commit → [TX 밖] send. <b>ack를 기다리지 않고</b> 다음 행으로 간다.
 *       send() 호출 자체가 실패하면(metadata 없음 등) 그 행을 실패로 기록하고 회차를 멈춘다 — 뒤 행은 맡지도 보내지도 않는다</li>
 *   <li>기다리기: 보낸 행의 broker 응답을 id 순서로 기다리며(모두 합쳐 send-wait 안) 행마다 [TX2] 자기 결과를 기록한다.
 *       이미 보낸 행은 앞 행이 실패해도 자기 ack대로 PUBLISHED다. 실패한 행만 backoff만큼 미뤄져 다음 회차가 건너뛴다</li>
 * </ol>
 * 묶어 보내는 이유 (11-06 실측): 한 행씩 ack를 기다리면 행당 약 12ms(broker 3대 · acks=all)라 초당 56건이 상한이었다.
 * 같은 key의 순서는 producer가 지킨다(idempotence · 같은 partition 안 보낸 순서).
 * <b>PUBLISHED는 broker 응답(ack)을 받은 뒤, 그 응답의 partition·offset으로만 기록한다.</b>
 * 외부 호출(send)을 DB TX로 감싸지 않는다 — 감싸면 ack 뒤 commit 실패가 "Kafka에는 있고 DB는 안 보냄"을 만들고,
 * 응답을 기다리는 동안 DB 연결과 행 잠금을 쥔다 (멘토링 11-02 원칙을 relay에도 적용).
 */
@Slf4j
@Service
public class OrderOutboxRelayService implements OrderOutboxRelayUseCase {

    private final OrderOutboxPort orderOutboxPort;
    private final MessageSendPort messageSendPort;
    private final OrderOutboxBackoff backoff;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final String topic;
    private final int batchSize;
    private final Duration sendWait;

    public OrderOutboxRelayService(
        OrderOutboxPort orderOutboxPort,
        MessageSendPort messageSendPort,
        OrderOutboxBackoff backoff,
        Clock clock,
        PlatformTransactionManager transactionManager,
        @Value("${kafka.topics.order-events}") String topic,
        @Value("${outbox.relay.batch-size}") int batchSize,
        @Value("${outbox.relay.send-wait}") Duration sendWait
    ) {
        this.orderOutboxPort = orderOutboxPort;
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
        List<OrderOutbox> due = orderOutboxPort.findDue(now(), batchSize);
        List<InFlight> inFlight = new ArrayList<>();
        String sendStoppedAt = null;
        for (OrderOutbox row : due) {
            InFlight sent = send(row.getEventId());
            if (sent == null) {
                sendStoppedAt = row.getEventId();
                break;
            }
            inFlight.add(sent);
        }

        long deadline = System.nanoTime() + sendWait.toNanos();
        int published = 0;
        String firstUnpublished = null;
        for (InFlight sent : inFlight) {
            if (awaitAndRecord(sent, deadline)) {
                published++;
            } else if (firstUnpublished == null) {
                firstUnpublished = sent.eventId();
            }
        }
        return new RelayResult(due.size(), published, firstUnpublished != null ? firstUnpublished : sendStoppedAt);
    }

    /** 보낸 행 하나 — broker 응답을 아직 받지 않았다 */
    private record InFlight(String eventId, CompletableFuture<SentPosition> ack) {}

    /**
     * 한 행을 맡아(SENDING) 보낸다. ack는 기다리지 않는다. 맡지 못했거나 send() 호출 자체가 실패했으면 null — 회차를 멈춘다.
     */
    private InFlight send(String eventId) {
        OrderOutbox claimed;
        try {
            claimed = inTransaction(eventId, outbox -> outbox.startSending(now()));
        } catch (RuntimeException e) {
            log.error("relay가 행을 맡지 못했습니다 — 회차를 멈춥니다. eventId={}", eventId, e);
            return null;
        }
        try {
            return new InFlight(eventId, messageSendPort.send(topic, claimed.getMessageKey(), claimed.getPayload()));
        } catch (RuntimeException e) {
            // send() 호출 자체의 실패 (metadata 없음, max.block.ms) — broker에 아무것도 가지 않았다
            recordFailure(eventId, e);
            return null;
        }
    }

    /**
     * 보낸 행의 broker 응답을 deadline까지 기다려 결과를 기록한다. PUBLISHED까지 기록했으면 true.
     */
    private boolean awaitAndRecord(InFlight sent, long deadline) {
        String eventId = sent.eventId();
        SentPosition position;
        try {
            long remaining = Math.max(0L, deadline - System.nanoTime());
            position = sent.ack().get(remaining, TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("broker 응답을 기다리다 중단됐습니다 — SENDING으로 남고 lease 뒤 다시 보냅니다. eventId={}", eventId);
            return false;
        } catch (ExecutionException e) {
            recordFailure(eventId, e.getCause());
            return false;
        } catch (TimeoutException e) {
            // send-wait 안에 응답 없음
            recordFailure(eventId, e);
            return false;
        }

        try {
            inTransaction(eventId, outbox -> outbox.markPublished(position.partition(), position.offset(), now()));
            return true;
        } catch (RuntimeException e) {
            log.error("broker는 받았지만(ack) PUBLISHED를 저장하지 못했습니다 — SENDING으로 남고 lease 뒤 같은 eventId로 다시 보냅니다"
                + "(consumer가 중복을 거름). eventId={}, partition={}, offset={}", eventId, position.partition(), position.offset(), e);
            return false;
        }
    }

    private void recordFailure(String eventId, Throwable cause) {
        String error = describe(cause);
        try {
            OrderOutbox after = inTransaction(eventId,
                outbox -> outbox.recordFailure(error, now(), backoff.delayAfter(outbox.getAttemptCount() + 1)));
            if (after.getStatus() == OrderOutboxStatus.BLOCKED) {
                log.error("[OUTBOX_BLOCKED] {}번 실패해 자동 재시도를 멈췄습니다 — 원인을 고친 뒤 재개하세요. eventId={}, orderId={}, cause={}",
                    after.getAttemptCount(), eventId, after.getOrderId(), error);
            } else {
                log.warn("send 실패 — {}에 다시 보냅니다. eventId={}, attempt={}, cause={}",
                    after.getNextAttemptAt(), eventId, after.getAttemptCount(), error);
            }
        } catch (RuntimeException e) {
            log.error("send 실패를 저장하지 못했습니다 — SENDING으로 남고 lease 뒤 다시 보냅니다. eventId={}, cause={}", eventId, error, e);
        }
    }

    /**
     * 행을 새로 읽어 전이하고 저장한다. 한 번의 짧은 TX — 실패하면 rollback되어 DB의 값은 그대로다.
     */
    private OrderOutbox inTransaction(String eventId, Consumer<OrderOutbox> transition) {
        return transaction.execute(status -> {
            OrderOutbox outbox = orderOutboxPort.findByEventId(eventId)
                .orElseThrow(() -> new IllegalStateException("Outbox 행이 없습니다: " + eventId));
            transition.accept(outbox);
            return orderOutboxPort.save(outbox);
        });
    }

    private ZonedDateTime now() {
        return ZonedDateTime.now(clock);
    }

    /**
     * 진단용 원인 문자열. 감싼 예외(예: KafkaException "Send failed")만으로는 원인이 안 보이므로 가장 안쪽 원인을 덧붙인다.
     */
    private static String describe(Throwable cause) {
        if (cause == null) {
            return "unknown";
        }
        String text = cause.getClass().getSimpleName() + ": " + cause.getMessage();
        Throwable root = cause;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        if (root != cause) {
            text += " (원인 " + root.getClass().getSimpleName() + ": " + root.getMessage() + ")";
        }
        return text;
    }
}
