package com.loopers.domains.order.in;

import com.loopers.domains.order.app.OrderApplicationService;
import com.loopers.domains.order.app.port.OrderConfirmedPayload;
import com.loopers.domains.order.out.OrderConfirmedMessageSerializer;
import com.loopers.support.kafka.OrderEventsTestConfig;
import com.loopers.support.kafka.OrderEventsTestConfig.ScriptedMessageSender;
import com.loopers.support.pg.MutablePaymentClock;
import com.loopers.utils.DatabaseCleanUp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

// C4(대조 실험): 스케줄러를 켠 컨텍스트에서 relay를 send 중에 막아 두고, 그동안 결제 스케줄 작업이 도는지 본다.
// 풀이 1이면 결제 작업이 돌지 않아 timeout으로 실패한다. timeout은 Red 근거로 쓰지 않고 풀 크기 단언(C3)을 주 증거로 한다.
// relay가 막힌 장면에 들어갔는지는 출력 줄로만 남긴다(Red 단계에서는 relay가 보내지 않아 막히지 않는다).
// 스케줄러를 켠 컨텍스트가 캐시에 남으면 공유 DB에서 결제 만료를 계속 돌려 다른 테스트를 깨므로 클래스가 끝나면 닫는다.
// 닫을 때 이미 돌던 스케줄 작업이 다음 테스트 클래스와 겹치지 않도록 끝날 때까지 기다린다.
@DirtiesContext
@SpringBootTest(properties = {
    "commerce.outbox.relay.enabled=true",
    "commerce.outbox.relay.fixed-delay=PT0.05S",
    "payment.lookup.scheduler.enabled=true",
    "payment.lookup.scheduler.fixed-delay=PT0.05S",
    "spring.task.scheduling.shutdown.await-termination=true",
    "spring.task.scheduling.shutdown.await-termination-period=15s"
})
@Import({PaymentLookupScheduleIntegrationTest.TimeConfig.class, OrderEventsTestConfig.class})
class OrderOutboxRelaySchedulerIsolationTest {
    private static final Instant NOW = Instant.parse("2026-10-29T01:00:00Z");

    @MockitoSpyBean private OrderApplicationService orderApplicationService;
    @Autowired private ScriptedMessageSender sender;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MutablePaymentClock clock;
    @Autowired private DatabaseCleanUp cleanup;

    @AfterEach
    void clean() {
        sender.reset();
        cleanup.truncateAllTables();
    }

    @Test
    @DisplayName("relay가 send에서 막혀 있는 동안에도 결제 재조회·만료 스케줄 작업이 제한 시간 안에 다시 호출된다")
    void paymentScheduleRunsWhileRelayIsBlocked() throws InterruptedException {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        sender.onSend(() -> {
            entered.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        clock.set(NOW);
        insertPending(1);
        try {
            boolean blocked = entered.await(3, TimeUnit.SECONDS);
            clearInvocations(orderApplicationService);

            verify(orderApplicationService, timeout(3_000).atLeast(2)).expireOverduePayments();

            System.out.println("[C4 대조] relay send 막힘 진입=" + blocked + ", 막힌 동안 결제 만료 작업 2회 이상 호출=true");
        } finally {
            release.countDown();
        }
    }

    private void insertPending(long orderId) {
        OrderConfirmedPayload payload = new OrderConfirmedPayload(String.valueOf(orderId), "order-confirmed:v1:" + orderId,
            orderId, NOW, List.of(new OrderConfirmedPayload.Item(1, 1)));
        LocalDateTime now = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);
        jdbc.update("insert into order_outbox(event_id, order_id, event_type, schema_version, message_key, payload, status,"
                + " attempt_count, row_failure_count, next_attempt_at, created_at) values (?, ?, 'OrderConfirmed', 1, ?, ?,"
                + " 'PENDING', 0, 0, ?, ?)",
            payload.eventId(), orderId, payload.key(), OrderConfirmedMessageSerializer.serialize(payload), now, now);
    }
}
