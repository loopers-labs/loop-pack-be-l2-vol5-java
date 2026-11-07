package com.loopers.interfaces.runner;

import com.loopers.application.order.OrderConfirmedBackfill;
import com.loopers.application.order.OrderEventRelay;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * 로컬 전용 Outbox 운영 진입점. local 프로필에서 기동할 때 인자로 받은 대상만 한 번 처리한다. HTTP 로 노출하지 않는다.
 * <pre>
 *   --outbox.local.backfill-order-ids=1,2               지정 주문의 누락 Outbox 를 저장된 확정 사실로 넣는다 (보내는 것은 주기 relay)
 *   --outbox.local.resume-event-ids=order-confirmed:v1:1 BLOCKED 를 같은 행 그대로 PENDING 으로 되돌리고 한 번 보낸다
 * </pre>
 * 인자가 없으면 아무것도 하지 않는다. 대상 하나가 실패해도 나머지는 계속하고, 실패는 eventId·orderId 와 함께 로그로 남긴다.
 */
@Slf4j
@Profile("local")
@Component
public class OrderEventLocalRunner implements ApplicationRunner {
    private final OrderConfirmedBackfill backfill;
    private final OrderEventRelay relay;
    private final List<Long> backfillOrderIds;
    private final List<String> resumeEventIds;

    public OrderEventLocalRunner(OrderConfirmedBackfill backfill, OrderEventRelay relay,
                                 @Value("${outbox.local.backfill-order-ids:}") String backfillOrderIds,
                                 @Value("${outbox.local.resume-event-ids:}") String resumeEventIds) {
        this.backfill = backfill;
        this.relay = relay;
        this.backfillOrderIds = split(backfillOrderIds).stream().map(Long::valueOf).toList();
        this.resumeEventIds = split(resumeEventIds);
    }

    private static List<String> split(String csv) {
        return Arrays.stream(csv.split(",")).map(String::trim).filter(value -> !value.isEmpty()).toList();
    }

    @Override
    public void run(ApplicationArguments args) {
        for (Long orderId : backfillOrderIds) {
            try {
                log.info("로컬 Outbox 복구 [orderId = {}, result = {}]", orderId, backfill.backfill(orderId));
            } catch (RuntimeException e) {
                log.error("로컬 Outbox 복구 실패 [orderId = {}]", orderId, e);
            }
        }
        for (String eventId : resumeEventIds) {
            try {
                log.info("로컬 Outbox 재개 [eventId = {}, run = {}]", eventId, relay.resumeAndRun(eventId));
            } catch (RuntimeException e) {
                log.error("로컬 Outbox 재개 실패 [eventId = {}]", eventId, e);
            }
        }
    }
}
