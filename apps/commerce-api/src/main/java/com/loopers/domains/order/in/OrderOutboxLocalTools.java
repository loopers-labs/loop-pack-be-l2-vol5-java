package com.loopers.domains.order.in;

import com.loopers.domains.order.app.OrderOutboxBackfill;
import com.loopers.domains.order.app.OrderOutboxResume;
import java.util.Arrays;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

// 로컬 전용 Outbox 운영 도구. 공개 API를 두지 않고 앱 시작 인자로만 받는다.
// --outbox.resume=order-confirmed:v1:42 : BLOCKED 행 재개. --outbox.backfill=41,42 : Outbox 행이 없는 확정 주문 복구(D121).
// 재개·복구한 행은 relay의 다음 회차가 보낸다.
@Slf4j
@Component
@Profile("local")
public class OrderOutboxLocalTools implements ApplicationRunner {
    private static final String RESUME_OPTION = "outbox.resume";
    private static final String BACKFILL_OPTION = "outbox.backfill";

    private final OrderOutboxResume orderOutboxResume;
    private final OrderOutboxBackfill orderOutboxBackfill;

    public OrderOutboxLocalTools(OrderOutboxResume orderOutboxResume, OrderOutboxBackfill orderOutboxBackfill) {
        this.orderOutboxResume = orderOutboxResume;
        this.orderOutboxBackfill = orderOutboxBackfill;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<String> eventIds = args.getOptionValues(RESUME_OPTION);
        if (eventIds != null) {
            for (String eventId : eventIds) {
                OrderOutboxResume.Result result = orderOutboxResume.resume(eventId);
                log.info("Outbox BLOCKED 재개 요청. eventId={}, result={}", eventId, result);
            }
        }
        List<String> orderIdArguments = args.getOptionValues(BACKFILL_OPTION);
        if (orderIdArguments != null) {
            List<Long> orderIds = orderIdArguments.stream()
                .flatMap(argument -> Arrays.stream(argument.split(",")))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(Long::valueOf)
                .toList();
            for (OrderOutboxBackfill.Outcome outcome : orderOutboxBackfill.ensure(orderIds)) {
                log.info("Outbox 복구 요청. orderId={}, result={}", outcome.orderId(), outcome.result());
            }
        }
    }
}
