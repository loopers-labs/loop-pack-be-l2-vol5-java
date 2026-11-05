package com.loopers.outbox.application;

import com.loopers.outbox.application.port.in.OrderOutboxRelayUseCase;
import com.loopers.outbox.application.port.out.MessageSendPort;
import com.loopers.outbox.application.port.out.OrderOutboxPort;
import com.loopers.outbox.domain.OrderOutboxBackoff;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;

/**
 * 단일 relay의 한 회차 (docs/week7/plan.md D-1).
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
        return new RelayResult(0, 0, null);
    }
}
