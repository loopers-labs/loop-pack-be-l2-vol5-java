package com.loopers.domains.order.app;

import com.loopers.domains.order.app.port.OrderConfirmedMessageSender;
import com.loopers.domains.order.app.port.OrderConfirmedOutbox;
import com.loopers.domains.order.app.port.OutboxMessage;
import com.loopers.domains.order.app.port.OutboxRelaySettings;
import com.loopers.domains.order.app.port.RelaySettings;
import com.loopers.domains.order.app.port.SendOutcome;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

// relay 한 회차(D112~D117). DB에 남은 보낼 사실을 저장된 key·payload 그대로 broker에 보내고, broker 확인을 받은 행만 PUBLISHED로 적는다.
// 전송은 직렬이고 조회·전송은 트랜잭션 밖, 상태 기록은 행마다 짧은 트랜잭션 하나다. 순서를 바꿔 PUBLISHED를 먼저 적으면
// "PUBLISHED인데 broker에 없음"이 생겨 다시 보내지 않으므로, 지금 순서에서 생길 수 있는 것은 "broker에 있는데 PENDING"(다음 회차 재전송)뿐이다.
@Service
public class OrderOutboxRelay {
    private static final int ROW_FAILURES_TO_BLOCK = 5;

    private final OrderConfirmedOutbox orderConfirmedOutbox;
    private final OrderConfirmedMessageSender orderConfirmedMessageSender;
    private final OutboxRelaySettings outboxRelaySettings;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public OrderOutboxRelay(OrderConfirmedOutbox orderConfirmedOutbox, OrderConfirmedMessageSender orderConfirmedMessageSender,
        OutboxRelaySettings outboxRelaySettings, TransactionTemplate transactionTemplate, Clock clock) {
        this.orderConfirmedOutbox = orderConfirmedOutbox;
        this.orderConfirmedMessageSender = orderConfirmedMessageSender;
        this.outboxRelaySettings = outboxRelaySettings;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    // 묶음이 꽉 찼고 회차 최대 시간 전이면 쉬지 않고 다음 묶음으로 간다. 최대 시간은 다음 묶음을 시작할지만 정하고 진행 중인 send를 끊지 않는다.
    // broker 수준 실패면 나머지 행을 손대지 않고 회차를 끝낸다(D113).
    public void runOnce() {
        RelaySettings settings = RelaySettings.sanitize(outboxRelaySettings.current().orElse(null));
        if (!settings.enabled()) {
            return;
        }
        Instant roundStartedAt = clock.instant();
        while (true) {
            List<OutboxMessage> batch = orderConfirmedOutbox.findDue(clock.instant(), settings.batchSize());
            for (OutboxMessage message : batch) {
                SendOutcome outcome = orderConfirmedMessageSender.send(message.key(), message.payload());
                // 인터럽트 플래그가 선 채로 JDBC·커넥션 풀을 부르면 연결을 얻다가 실패할 수 있어, 기록하지 않고 바로 끝낸다.
                // 기록하지 못한 행은 PENDING으로 남아 다음 회차에 다시 보낸다(streamer 멱등 소비가 중복을 흡수).
                if (Thread.currentThread().isInterrupted()) {
                    return;
                }
                if (!record(message, outcome)) {
                    return;
                }
            }
            boolean full = batch.size() == settings.batchSize();
            boolean timeLeft = Duration.between(roundStartedAt, clock.instant()).compareTo(settings.maxRound()) < 0;
            if (!full || !timeLeft) {
                return;
            }
        }
    }

    // 회차를 이어 가면 true. 모든 기록은 PENDING 행에만 적용된다(D115, 어댑터의 조건부 갱신).
    private boolean record(OutboxMessage message, SendOutcome outcome) {
        Instant now = clock.instant();
        return switch (outcome) {
            case SendOutcome.Published published -> {
                transactionTemplate.executeWithoutResult(status -> orderConfirmedOutbox.markPublished(message.id(), now));
                yield true;
            }
            case SendOutcome.BrokerFailure failure -> {
                Instant next = now.plus(OutboxRetryPolicy.backoff(message.attemptCount() + 1));
                transactionTemplate.executeWithoutResult(
                    status -> orderConfirmedOutbox.markBrokerFailure(message.id(), next, failure.error()));
                yield false;
            }
            case SendOutcome.RowFailure failure -> {
                Instant next = now.plus(OutboxRetryPolicy.backoff(message.attemptCount() + 1));
                boolean blocked = message.rowFailureCount() + 1 >= ROW_FAILURES_TO_BLOCK;
                transactionTemplate.executeWithoutResult(
                    status -> orderConfirmedOutbox.markRowFailure(message.id(), next, failure.error(), blocked));
                yield true;
            }
        };
    }
}
