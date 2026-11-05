package com.loopers.interfaces.recovery;

import com.loopers.application.order.OrderOutboxRecovery;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 로컬 운영 진입점. 시작 인자로 대상 주문을 명시할 때만 등록되며 공개 API로 노출하지 않는다.
 * 대상이 실패하면 예외로 앱 시작을 멈춰 원인을 드러낸다. 추가된 행은 relay가 보낸다.
 */
@Component
@ConditionalOnProperty(name = "commerce.order-events.recovery.order-ids")
@Slf4j
public class OrderOutboxRecoveryRunner implements ApplicationRunner {
    private final OrderOutboxRecovery recovery;
    private final List<Long> orderIds;

    public OrderOutboxRecoveryRunner(OrderOutboxRecovery recovery,
                                     @Value("${commerce.order-events.recovery.order-ids}") List<Long> orderIds) {
        this.recovery = recovery;
        this.orderIds = orderIds;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (Long orderId : orderIds) {
            log.info("Order outbox recovery: orderId={}, added={}", orderId, recovery.recover(orderId));
        }
    }
}
