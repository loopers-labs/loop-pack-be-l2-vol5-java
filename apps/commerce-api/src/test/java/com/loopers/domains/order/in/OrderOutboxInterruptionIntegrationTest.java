package com.loopers.domains.order.in;

import com.loopers.domains.order.app.OrderOutboxRelay;
import com.loopers.domains.order.app.port.OutboxRelaySettings;
import com.loopers.domains.payment.out.TestPgServer.CreateBehavior;
import com.loopers.support.kafka.OrderEventsProbe;
import com.loopers.support.kafka.OrderEventsTestConfig.FailingOrderConfirmedOutbox;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 7주차 3단계: 과제 6장의 두 중단 구간을 실제 MySQL·Kafka와 실제 relay·Outbox 어댑터로 재현한다.
// 중단은 예외로 회차를 끊어 재현한 것이다. 프로세스 종료(JVM·커넥션·producer 정리)는 흉내 내지 않고, 새 relay도 같은 producer Bean을 쓴다.
// "재시작"은 같은 Bean으로 새 relay 인스턴스를 만들어 같은 DB·broker·eventId로 한 회차를 돌리는 것으로 본다.
// consumer 쪽 중복 흡수(구매 활동 1회)는 4단계 E2E가 본다. 여기서는 broker 기록과 Outbox 상태까지만 본다.
class OrderOutboxInterruptionIntegrationTest extends OrderEventsIntegrationSupport {
    @Autowired private OutboxRelaySettings outboxRelaySettings;
    @Autowired private TransactionTemplate transactionTemplate;

    @Test
    @DisplayName("주문 확정 commit 뒤 relay가 돌기 전에 끊기면 Outbox는 PENDING·broker는 0건이고, 새로 만든 relay 한 회차가 저장된 key·payload 그대로 1건 보내 PUBLISHED로 바꾼다")
    void interruptedBeforeRelayKeepsPendingAndNewRelayPublishesOnce() throws Exception {
        long orderId = confirmedOrder();

        // ① 확정은 commit됐고 relay는 아직 돌지 않았다.
        assertThat(orderStatus(orderId)).isEqualTo("CONFIRMED");
        assertThat(stock(first)).isEqualTo(3);
        assertThat(stock(second)).isEqualTo(4);
        Map<String, Object> pending = outboxRow(orderId);
        assertThat(pending).containsEntry("status", "PENDING").containsEntry("attempt_count", 0).containsEntry("published_at", null);
        assertThat(recordsOf(orderId)).isEmpty();

        // ①' 새 relay 한 회차.
        newRelay().runOnce();

        List<ConsumerRecord<String, String>> records = recordsOf(orderId);
        assertThat(records).hasSize(1);
        assertThat(records.getFirst().key()).isEqualTo(pending.get("message_key"));
        assertThat(records.getFirst().value()).isEqualTo(pending.get("payload"));
        assertThat(outboxRow(orderId))
            .containsEntry("status", "PUBLISHED")
            .containsEntry("attempt_count", 0)
            .containsEntry("published_at", LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("broker 확인 뒤 PUBLISHED 기록 전에 회차가 끊기면 행은 PENDING이고, 새로 만든 relay가 다시 보내 broker에 같은 eventId가 offset이 다른 2건으로 남은 뒤 PUBLISHED가 된다")
    void interruptedAfterAckBeforeRecordRepublishesAsNewRecord() throws Exception {
        long orderId = confirmedOrder();
        Map<String, Object> pending = outboxRow(orderId);

        // ② 기존 relay: send와 broker 확인까지 끝낸 뒤 PUBLISHED 기록에서 예외로 회차를 끊는다.
        failingOutbox.failOnNextMarkPublished();
        assertThatThrownBy(relay::runOnce).hasMessage(FailingOrderConfirmedOutbox.INJECTED_MARK_PUBLISHED_FAILURE);

        List<RecordMetadata> acked = failingKafka.acked();
        assertThat(acked).hasSize(1);
        RecordMetadata firstAck = acked.getFirst();
        assertThat(outboxRow(orderId))
            .containsEntry("status", "PENDING")
            .containsEntry("attempt_count", 0)
            .containsEntry("published_at", null);
        List<ConsumerRecord<String, String>> afterFirst = recordsOf(orderId);
        assertThat(afterFirst).hasSize(1);
        assertThat(afterFirst.getFirst().partition()).isEqualTo(firstAck.partition());
        assertThat(afterFirst.getFirst().offset()).isEqualTo(firstAck.offset());

        // ②' 새 relay 한 회차: 같은 행을 다시 보내 broker에 새 record가 생긴다(같은 record를 다시 읽는 재전달이 아니다).
        newRelay().runOnce();

        List<ConsumerRecord<String, String>> records = recordsOf(orderId);
        assertThat(records).hasSize(2);
        assertThat(records.get(0).offset()).isEqualTo(firstAck.offset());
        assertThat(records.get(1).offset()).isNotEqualTo(records.get(0).offset());
        assertThat(records.get(1).partition()).isEqualTo(records.get(0).partition());
        assertThat(records).extracting(ConsumerRecord::value).containsOnly((String) pending.get("payload"));
        assertThat(outboxRow(orderId))
            .containsEntry("status", "PUBLISHED")
            .containsEntry("attempt_count", 0)
            .containsEntry("published_at", LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
    }

    private long confirmedOrder() throws Exception {
        long orderId = newOrder();
        server.createBehavior(CreateBehavior.ACCEPT_SUCCESS);
        confirm(orderId, UUID.randomUUID().toString()).andExpect(status().isOk());
        return orderId;
    }

    private OrderOutboxRelay newRelay() {
        return new OrderOutboxRelay(failingOutbox, sender, outboxRelaySettings, transactionTemplate, clock);
    }

    // relay는 broker 확인까지 기다리므로 flush 뒤 끝까지 읽으면 그 eventId의 record가 모두 보인다.
    private List<ConsumerRecord<String, String>> recordsOf(long orderId) {
        failingKafka.flush();
        return probe.readThrough(from).stream()
            .filter(record -> eventId(orderId).equals(OrderEventsProbe.eventId(record)))
            .toList();
    }
}
