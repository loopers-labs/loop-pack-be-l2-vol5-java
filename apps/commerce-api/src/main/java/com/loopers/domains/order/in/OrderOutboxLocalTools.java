package com.loopers.domains.order.in;

import com.loopers.domains.order.app.OrderOutboxResume;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

// 로컬 전용 Outbox 운영 도구. 공개 API를 두지 않고 앱 시작 인자로만 받는다(예: --outbox.resume=order-confirmed:v1:42).
// 재개한 행은 relay의 다음 회차가 보낸다.
@Slf4j
@Component
@Profile("local")
public class OrderOutboxLocalTools implements ApplicationRunner {
    private static final String RESUME_OPTION = "outbox.resume";

    private final OrderOutboxResume orderOutboxResume;

    public OrderOutboxLocalTools(OrderOutboxResume orderOutboxResume) {
        this.orderOutboxResume = orderOutboxResume;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<String> eventIds = args.getOptionValues(RESUME_OPTION);
        if (eventIds == null) {
            return;
        }
        for (String eventId : eventIds) {
            OrderOutboxResume.Result result = orderOutboxResume.resume(eventId);
            log.info("Outbox BLOCKED 재개 요청. eventId={}, result={}", eventId, result);
        }
    }
}
