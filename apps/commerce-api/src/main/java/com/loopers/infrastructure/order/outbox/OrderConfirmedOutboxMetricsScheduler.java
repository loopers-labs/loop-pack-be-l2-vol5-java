package com.loopers.infrastructure.order.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// Relay 실행 여부와 관계없이 미해결 Outbox 집계를 갱신해 Relay 중단도 지표로 드러낸다.
// 기동 직후 한 번 갱신해 첫 주기 동안 실제 backlog가 0으로 보이지 않게 한다.
@Slf4j
@RequiredArgsConstructor
@Component
@ConditionalOnProperty(prefix = "order.confirmed-event.outbox.metrics", name = "enabled", havingValue = "true")
public class OrderConfirmedOutboxMetricsScheduler {

    private final OrderConfirmedOutboxMetrics outboxMetrics;

    @Scheduled(fixedDelayString = "${order.confirmed-event.outbox.metrics.interval}", initialDelay = 0)
    public void refresh() {
        try {
            outboxMetrics.refresh();
        } catch (RuntimeException failure) {
            log.warn("주문 확정 Outbox 집계 metric 갱신에 실패해 직전 값을 유지합니다.", failure);
        }
    }
}
