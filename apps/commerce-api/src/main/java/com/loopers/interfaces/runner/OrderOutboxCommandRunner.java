package com.loopers.interfaces.runner;

import com.loopers.application.order.dto.OrderBackfillInfo;
import com.loopers.application.order.dto.OrderOutboxResumeInfo;
import com.loopers.application.order.facade.OrderOutboxOperations;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * 로컬에서 실행 인자로 Outbox 운영 동작을 한 번 부르고 끝냄. HTTP 로 노출하지 않음 (7주차 설계 5.2).
 * 예: --server.port=0 --management.server.port=0 --order-outbox.command=backfill --order-outbox.order-ids=1,2
 * 웹을 끄면(web-application-type=none) 관리자 경계의 HttpSecurity 가 없어 기동하지 못함
 */
@Slf4j
@Profile("local")
@ConditionalOnProperty(name = "order-outbox.command")
@Component
public class OrderOutboxCommandRunner implements ApplicationRunner {

    static final int SUCCESS = 0;
    static final int FAILURE = 1;
    static final int USAGE = 2;

    private final OrderOutboxOperations operations;
    private final Environment environment;
    private final ConfigurableApplicationContext context;

    public OrderOutboxCommandRunner(OrderOutboxOperations operations, Environment environment, ConfigurableApplicationContext context) {
        this.operations = operations;
        this.environment = environment;
        this.context = context;
    }

    /** 스케줄러 · producer 스레드가 남아 프로세스가 끝나지 않으므로 직접 종료함 */
    @Override
    public void run(ApplicationArguments args) {
        int exitCode = execute(environment.getProperty("order-outbox.command"));
        System.exit(SpringApplication.exit(context, () -> exitCode));
    }

    int execute(String command) {
        return switch (command) {
            case "run-once" -> runOnce();
            case "resume" -> resume(environment.getProperty("order-outbox.event-id"));
            case "backfill" -> backfill(environment.getProperty("order-outbox.order-ids"));
            default -> usage("알 수 없는 명령: " + command);
        };
    }

    private int runOnce() {
        boolean ran = operations.runOnce();
        log.info("Outbox relay 한 회차 {}", ran ? "실행함" : "건너뜀(이전 회차 진행 중)");
        return SUCCESS;
    }

    private int resume(String eventId) {
        if (eventId == null || eventId.isBlank()) {
            return usage("resume 에는 --order-outbox.event-id 가 필요함");
        }
        OrderOutboxResumeInfo info = operations.resume(eventId);
        log.info("Outbox 재개. eventId={} resumed={} status={}", info.eventId(), info.resumed(), info.status());
        return SUCCESS;
    }

    private int backfill(String orderIds) {
        if (orderIds == null || orderIds.isBlank()) {
            return usage("backfill 에는 --order-outbox.order-ids 가 필요함. 전체 소급은 하지 않음");
        }
        List<Long> ids = Arrays.stream(orderIds.split(","))
            .map(String::trim)
            .map(Long::valueOf)
            .toList();
        List<OrderBackfillInfo> results = operations.backfill(ids);
        results.forEach(info -> log.info("Outbox backfill. orderId={} result={} error={}", info.orderId(), info.result(), info.error()));
        return results.stream().anyMatch(info -> info.error() != null) ? FAILURE : SUCCESS;
    }

    private int usage(String message) {
        log.error("{}. 명령: run-once | resume --order-outbox.event-id=... | backfill --order-outbox.order-ids=1,2", message);
        return USAGE;
    }
}
