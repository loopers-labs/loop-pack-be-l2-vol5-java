package com.loopers.infrastructure.order.outbox;

import com.loopers.application.order.event.OrderConfirmedOutboxBackfillService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

@Slf4j
@RequiredArgsConstructor
@Component
@Profile("local")
@ConditionalOnProperty(prefix = "order.confirmed-event.outbox.backfill", name = "order-ids")
public class OrderConfirmedOutboxBackfillRunner implements ApplicationRunner {

    private final OrderConfirmedOutboxBackfillService backfillService;

    @Value("${order.confirmed-event.outbox.backfill.order-ids}")
    private String orderIds;

    @Override
    public void run(ApplicationArguments args) {
        List<Long> targets = Arrays.stream(orderIds.split(","))
            .map(String::trim)
            .map(Long::valueOf)
            .distinct()
            .toList();
        if (targets.isEmpty() || targets.stream().anyMatch(id -> id <= 0)) {
            throw new IllegalArgumentException("Outbox 복구 대상은 양수 주문 ID 목록이어야 합니다.");
        }
        for (Long orderId : targets) {
            OrderConfirmedOutboxBackfillService.BackfillResult result = backfillService.backfill(orderId);
            log.info("주문 확정 Outbox 복구를 확인했습니다. orderId={}, eventId={}, inserted={}",
                result.orderId(), result.eventId(), result.inserted());
        }
    }
}
