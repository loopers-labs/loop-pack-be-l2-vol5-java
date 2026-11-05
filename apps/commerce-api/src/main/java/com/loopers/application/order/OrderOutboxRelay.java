package com.loopers.application.order;

import com.loopers.domain.order.OrderConfirmationPublisher;
import com.loopers.domain.order.OrderOutboxDelivery;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 저장된 PENDING 행을 직렬로 전송한다. 상태 변경은 이 실행 흐름만 수행하므로 늦은 send 응답이 행을 바꾸지 않는다.
 * 잠금은 한 프로세스 안의 겹침만 막는다. 여러 프로세스의 동시 실행은 설정으로 한 곳에서만 켜서 피한다.
 */
@Component
@ConditionalOnProperty(name = "commerce.order-events.enabled", havingValue = "true")
@Slf4j
public class OrderOutboxRelay {
    private final OrderConfirmationPublisher publisher;
    private final OrderOutboxTransactions transactions;
    private final int batchSize;
    private final Duration firstRetry;
    private final Duration maxRetry;
    private final ReentrantLock running = new ReentrantLock();

    public OrderOutboxRelay(OrderConfirmationPublisher publisher, OrderOutboxTransactions transactions,
                            @Value("${commerce.order-events.relay.batch-size:200}") int batchSize,
                            @Value("${commerce.order-events.relay.first-retry:2s}") Duration firstRetry,
                            @Value("${commerce.order-events.relay.max-retry:60s}") Duration maxRetry) {
        this.publisher = publisher;
        this.transactions = transactions;
        this.batchSize = batchSize;
        this.firstRetry = firstRetry;
        this.maxRetry = maxRetry;
    }

    /** 한 회차를 실행하고 PUBLISHED로 저장한 건수를 돌려준다. 전송이 실패하면 남은 행은 다음 회차로 넘긴다. */
    public int runOnce() {
        if (!running.tryLock()) {
            return 0;
        }
        try {
            int published = 0;
            for (Long id : transactions.findDueIds(batchSize)) {
                if (!deliver(id)) {
                    break;
                }
                published++;
            }
            return published;
        } finally {
            running.unlock();
        }
    }

    private boolean deliver(Long id) {
        OrderOutboxDelivery delivery = transactions.startAttempt(id);
        try {
            publisher.publish(delivery);
        } catch (RuntimeException exception) {
            log.warn("Order event send failed: eventId={}", delivery.eventId(), exception);
            transactions.scheduleRetry(id, exception.getMessage(), firstRetry, maxRetry);
            return false;
        }
        transactions.markPublished(id);
        return true;
    }
}
