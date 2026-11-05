package com.loopers.interfaces.api.order;

import com.loopers.infrastructure.payment.TestPgServer.CreateBehavior;
import com.loopers.support.kafka.OrderEventsTestConfig.FailingKafkaTemplate;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(OutputCaptureExtension.class)
class OrderConfirmedPublishFailureTest extends OrderEventsIntegrationSupport {
    private static final String FAILURE_METRIC = "commerce.order.events.publish.failed";

    @Autowired private MeterRegistry meterRegistry;

    @Test
    @DisplayName("send 결과가 실패로 끝나도 confirm은 저장된 결과 200·CONFIRMED이고 예약 재고는 소비된 채이며 실패 로그·지표가 1 늘고 record는 없다")
    void asyncSendFailureKeepsConfirmedOrder(CapturedOutput output) throws Exception {
        long orderId = newOrder();
        double before = failures();
        server.createBehavior(CreateBehavior.ACCEPT_SUCCESS);
        failingKafka.fail(FailingKafkaTemplate.Failure.ASYNC_FAILED);

        confirm(orderId, UUID.randomUUID().toString()).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
            .andExpect(jsonPath("$.data.paymentResult").value("SUCCESS"));

        awaitFailures(before + 1);
        assertThat(orderStatus(orderId)).isEqualTo("CONFIRMED");
        assertThat(stock(first)).isEqualTo(3);
        assertThat(stock(second)).isEqualTo(4);
        assertThat(server.createCalls()).isEqualTo(1);
        assertThat(output.getAll()).contains("주문 확정 이벤트 발행 실패").contains("eventId=" + eventId(orderId));
        failingKafka.fail(FailingKafkaTemplate.Failure.NONE);
        assertNoEvent(orderId);
    }

    @Test
    @DisplayName("callback으로 확정하는 중 send가 바로 예외를 던져도 callback은 200이고 주문은 CONFIRMED로 남으며 실패 지표가 1 는다")
    void syncSendFailureKeepsCallbackConfirmation() throws Exception {
        long orderId = newOrder();
        server.createBehavior(CreateBehavior.ACCEPT_PENDING);
        confirm(orderId, UUID.randomUUID().toString()).andExpect(status().isAccepted());
        server.changeStatus(transactionKey(orderId), "SUCCESS");
        double before = failures();
        failingKafka.fail(FailingKafkaTemplate.Failure.SYNC_THROW);

        callback(orderId, "SUCCESS").andExpect(status().isOk());

        assertThat(failures()).isEqualTo(before + 1);
        assertThat(orderStatus(orderId)).isEqualTo("CONFIRMED");
        assertThat(stock(first)).isEqualTo(3);
        assertThat(jdbc.queryForObject("select result from payment_records where order_id = ?", String.class, orderId))
            .isEqualTo("SUCCESS");
    }

    private double failures() {
        Counter counter = meterRegistry.find(FAILURE_METRIC).tag("eventType", "OrderConfirmed").counter();
        return counter == null ? 0 : counter.count();
    }

    private void awaitFailures(double expected) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        while (Instant.now().isBefore(deadline)) {
            if (failures() >= expected) {
                return;
            }
            Thread.onSpinWait();
        }
        assertThat(failures()).isEqualTo(expected);
    }
}
