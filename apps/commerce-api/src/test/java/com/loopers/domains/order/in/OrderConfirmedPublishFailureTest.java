package com.loopers.domains.order.in;

import com.loopers.domains.payment.out.TestPgServer.CreateBehavior;
import com.loopers.support.kafka.OrderEventsTestConfig.FailingKafkaTemplate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 7주차 2단계(D95): send는 확정 경로가 아니라 relay가 한다. 실제 KafkaTemplate으로 broker 수준 실패(Kafka TimeoutException)를 주입한다.
class OrderConfirmedPublishFailureTest extends OrderEventsIntegrationSupport {

    @Test
    @DisplayName("relay의 send 결과가 broker 수준 실패로 끝나도 주문은 CONFIRMED·예약 소비 그대로이고 Outbox 행은 PENDING으로 남아 시도 1회·다음 시도 1초 뒤가 기록된다")
    void asyncSendFailureKeepsConfirmedOrder() throws Exception {
        long orderId = newOrder();
        server.createBehavior(CreateBehavior.ACCEPT_SUCCESS);
        confirm(orderId, UUID.randomUUID().toString()).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
            .andExpect(jsonPath("$.data.paymentResult").value("SUCCESS"));
        failingKafka.fail(FailingKafkaTemplate.Failure.ASYNC_FAILED);

        relay.runOnce();

        assertThat(orderStatus(orderId)).isEqualTo("CONFIRMED");
        assertThat(stock(first)).isEqualTo(3);
        assertThat(stock(second)).isEqualTo(4);
        assertThat(server.createCalls()).isEqualTo(1);
        assertBrokerFailureRecorded(orderId);
        failingKafka.fail(FailingKafkaTemplate.Failure.NONE);
        assertThat(eventIdsOnBroker()).doesNotContain(eventId(orderId));
    }

    @Test
    @DisplayName("callback으로 확정한 뒤 relay의 send가 바로 예외를 던져도 주문은 CONFIRMED·결제 SUCCESS로 남고 Outbox 행은 PENDING·시도 1회다")
    void syncSendFailureKeepsCallbackConfirmation() throws Exception {
        long orderId = newOrder();
        server.createBehavior(CreateBehavior.ACCEPT_PENDING);
        confirm(orderId, UUID.randomUUID().toString()).andExpect(status().isAccepted());
        server.changeStatus(transactionKey(orderId), "SUCCESS");
        callback(orderId, "SUCCESS").andExpect(status().isOk());
        failingKafka.fail(FailingKafkaTemplate.Failure.SYNC_THROW);

        relay.runOnce();

        assertThat(orderStatus(orderId)).isEqualTo("CONFIRMED");
        assertThat(stock(first)).isEqualTo(3);
        assertThat(jdbc.queryForObject("select result from payment_records where order_id = ?", String.class, orderId))
            .isEqualTo("SUCCESS");
        assertBrokerFailureRecorded(orderId);
    }

    private void assertBrokerFailureRecorded(long orderId) {
        Map<String, Object> row = outboxRow(orderId);
        assertThat(row)
            .containsEntry("status", "PENDING")
            .containsEntry("attempt_count", 1)
            .containsEntry("row_failure_count", 0)
            .containsEntry("next_attempt_at", LocalDateTime.parse("2026-10-29T01:00:01"))
            .containsEntry("published_at", null);
        assertThat((String) row.get("last_error")).contains(FailingKafkaTemplate.INJECTED_FAILURE);
    }
}
