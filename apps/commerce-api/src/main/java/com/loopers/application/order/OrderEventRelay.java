package com.loopers.application.order;

import com.loopers.domain.order.OrderEventDelivery;
import com.loopers.domain.order.OrderEventSender;
import com.loopers.domain.order.PendingOrderEvent;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
public class OrderEventRelay {

    private final OrderEventRelayTransactions transactions;
    private final OrderEventSender sender;

    public List<OrderEventDelivery> sendDue(int limit) {
        List<PendingOrderEvent> due = transactions.due(limit);
        return due.isEmpty() ? List.of() : sender.send(due);
    }

    public void record(List<OrderEventDelivery> deliveries) {
        if (!deliveries.isEmpty()) {
            transactions.record(deliveries);
        }
    }

    public int relayOnce(int limit) {
        List<OrderEventDelivery> deliveries = sendDue(limit);
        record(deliveries);
        return deliveries.size();
    }

    @Slf4j
    @Component
    @ConditionalOnProperty(name = "outbox.relay.enabled", havingValue = "true")
    static class Schedule {

        static final String THREAD_NAME = "outbox-relay";

        private final OrderEventRelay relay;
        private final int batchSize;
        private final Duration interval;
        private final Duration shutdownWait;
        private final ScheduledExecutorService executor =
            Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name(THREAD_NAME).factory());

        Schedule(OrderEventRelay relay,
                 @Value("${outbox.relay.batch-size:100}") int batchSize,
                 @Value("${outbox.relay.interval:1s}") Duration interval,
                 @Value("${outbox.relay.shutdown-wait:40s}") Duration shutdownWait) {
            this.relay = relay;
            this.batchSize = batchSize;
            this.interval = interval;
            this.shutdownWait = shutdownWait;
        }

        @PostConstruct
        void start() {
            executor.scheduleWithFixedDelay(this::relayUntilDrained,
                interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
        }

        void relayUntilDrained() {
            try {
                int sent;
                do {
                    sent = relay.relayOnce(batchSize);
                } while (sent == batchSize);
            } catch (RuntimeException e) {
                log.error("outbox relay 회차가 실패했다. 다음 주기에 다시 고른다", e);
            }
        }

        @PreDestroy
        void stop() throws InterruptedException {
            executor.shutdown();
            if (!executor.awaitTermination(shutdownWait.toMillis(), TimeUnit.MILLISECONDS)) {
                log.warn("outbox relay 가 {} 안에 끝나지 않아 멈춘다. 상태를 저장하지 못한 행은 PENDING 으로 남아 다시 보낸다",
                    shutdownWait);
                executor.shutdownNow();
            }
        }
    }
}
