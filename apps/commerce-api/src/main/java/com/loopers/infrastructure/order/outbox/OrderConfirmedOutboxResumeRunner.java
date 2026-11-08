package com.loopers.infrastructure.order.outbox;

import com.loopers.application.time.ApplicationTime;
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
@Component
@Profile("local")
@ConditionalOnProperty(prefix = "order.confirmed-event.outbox.resume", name = "event-ids")
public class OrderConfirmedOutboxResumeRunner implements ApplicationRunner {

    private final OrderConfirmedOutboxStateService stateService;
    private final ApplicationTime applicationTime;
    private final OrderConfirmedOutboxRelay relay;
    private final String eventIds;

    public OrderConfirmedOutboxResumeRunner(
        OrderConfirmedOutboxStateService stateService,
        ApplicationTime applicationTime,
        OrderConfirmedOutboxRelay relay,
        @Value("${order.confirmed-event.outbox.resume.event-ids}") String eventIds
    ) {
        this.stateService = stateService;
        this.applicationTime = applicationTime;
        this.relay = relay;
        this.eventIds = eventIds;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<String> targets = Arrays.stream(eventIds.split(",", -1))
            .map(String::trim)
            .distinct()
            .toList();
        if (targets.isEmpty() || targets.stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("Outbox 수동 재시도 대상은 비어 있지 않은 eventId 목록이어야 합니다.");
        }
        for (String eventId : targets) {
            OrderConfirmedOutboxStateService.ResumeResult result = stateService.resumeBlocked(
                eventId, applicationTime.now());
            log.warn("주문 확정 Outbox를 담당자 요청으로 수동 재시도했습니다. eventId={}, orderId={}, attemptCount={}, "
                    + "cycleAttemptCount={}", result.eventId(), result.orderId(), result.attemptCount(),
                result.cycleAttemptCount());
            if (!relay.runEventOnce(eventId)) {
                log.warn("주문 확정 Outbox Relay가 이미 실행 중이라 수동 재시도한 행은 PENDING으로 남았습니다. eventId={}",
                    eventId);
            }
        }
    }
}
