package com.loopers.application.order;

import com.loopers.domain.order.OrderEventOutbox;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class OrderEventResume {

    public enum Result {
        RESUMED, NOT_BLOCKED
    }

    private final OrderEventOutbox outbox;

    @Transactional
    public Map<String, Result> resume(List<String> eventIds) {
        Map<String, Result> results = new LinkedHashMap<>();
        for (String eventId : eventIds) {
            results.put(eventId, outbox.resume(eventId) ? Result.RESUMED : Result.NOT_BLOCKED);
        }
        return results;
    }

    @Slf4j
    @Component
    @ConditionalOnProperty(name = "outbox.resume.event-ids")
    public static class Runner implements ApplicationRunner {

        private final OrderEventResume resume;
        private final List<String> eventIds;

        Runner(OrderEventResume resume, @Value("${outbox.resume.event-ids}") List<String> eventIds) {
            this.resume = resume;
            this.eventIds = eventIds;
        }

        @Override
        public void run(ApplicationArguments args) {
            resume.resume(eventIds).forEach((eventId, result) -> {
                if (result == Result.RESUMED) {
                    log.info("BLOCKED 주문 이벤트를 PENDING 으로 되돌렸다. relay 가 다시 보낸다: eventId={}", eventId);
                } else {
                    log.warn("재개 대상이 BLOCKED 가 아니어서 그대로 둔다: eventId={}", eventId);
                }
            });
        }
    }
}
