package com.loopers.payment.scenario;

import com.loopers.order.domain.OrderStatus;
import com.loopers.outbox.adapter.out.persistence.EventOutboxJpaRepository;
import com.loopers.outbox.domain.EventOutbox;
import com.loopers.payment.domain.Payment;
import com.loopers.payment.domain.PaymentStatus;
import com.loopers.testcontainers.KafkaTestContainersConfig;
import com.loopers.utils.KafkaTopicReader;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;

/**
 * S-13 · E-4 (W6 plan.md D-8, C-8·C-9) — 직접 after-commit 발행의 누락 구간.
 * 주문 확정 TX와 broker 기록은 원자적으로 묶이지 않는다. commit 뒤 send 전에 멈추면(통제된 hook) 주문은 확정으로 남고 이벤트만 빠진다.
 * 주문을 되돌리지 않고 PG를 다시 부르지 않으며, 빠진 것은 발행 기록의 published_at null로 드러난다 — W7 Outbox relay의 출발점.
 * <p>
 * "send 전 중단"은 OS 강제 종료가 아니라 publish 호출을 한 번 건너뛰는 통제된 hook이다. 프로세스 종료와 같은 결과(send 콜백 없음)를 만든다.
 * 실행 결과의 사건 사실은 표준 출력의 [E-4] 줄로 남기고 docs/week6/incident-w6.md에 옮긴다.
 */
class S13PublishGapScenarioTest extends PaymentScenarioSupport {

    @Autowired
    private EventOutboxJpaRepository eventOutboxJpaRepository;

    @Value("${kafka.topics.order-events}")
    private String orderEventsTopic;

    @DisplayName("S-13 E-4 두 주문을 실제 API·로컬 PG callback으로 확정한다. 첫 주문은 commit 뒤 send 직전에 멈춰 CONFIRMED·예약 CONSUMED이지만 record가 없고 미발행으로 조회된다. 둘째 주문은 broker까지 간다.")
    @Test
    void confirmedOrderStaysButEventIsMissingWhenStoppedBeforeSend() throws Exception {
        // arrange: 첫 publish 한 번만 건너뛴다 (commit 뒤 send 직전 중단). 그다음부터는 실제로 보낸다.
        doNothing().doCallRealMethod().when(outboxPublishUseCase).publish(any());

        // act: 주문 A(에어맥스 2) → 주문 B(에어맥스 1) 순서로 실제 결제·callback 경로를 탄다
        Long missing = confirmByCallback(2, "attempt-a");
        Long published = confirmByCallback(1, "attempt-b");

        // assert: 주문 A — 확정·예약 소비는 남고, 이벤트만 빠졌다
        EventOutbox missingRow = outboxOf(missing);
        assertThat(orderStatusOf(missing)).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(reservationsOf(missing)).containsExactly("CONSUMED");
        assertThat(missingRow.getPublishedAt()).isNull();
        assertThat(missingRow.getLastError()).isNull(); // send 콜백조차 오지 않았다
        assertThat(recordsOf(missingRow.getEventId(), Duration.ofSeconds(3))).isEmpty();

        // assert: 주문 B — broker에 기록되고 성공 표시가 남는다
        EventOutbox publishedRow = await().atMost(WAIT).until(() -> outboxOf(published), row -> row.getPublishedAt() != null);
        List<ConsumerRecord<String, String>> publishedRecords = recordsOf(publishedRow.getEventId(), Duration.ofSeconds(10));
        assertThat(publishedRecords).hasSize(1);

        // assert: 누락 조회(ADR-W6-03)는 A만 찾는다
        List<String> unpublished = eventOutboxJpaRepository.findUnpublishedCreatedBefore(ZonedDateTime.now().plusMinutes(1)).stream()
            .map(EventOutbox::getEventId).toList();
        assertThat(unpublished).containsExactly(missingRow.getEventId());
        assertThat(PG.createCalls()).isEqualTo(2);

        printFacts("미발행", missing, missingRow, List.of());
        printFacts("정상 발행", published, publishedRow, publishedRecords);
    }

    @DisplayName("S-13 send가 실패하면(broker 응답 없음 주입) 주문은 CONFIRMED로 두고 PG를 다시 부르지 않으며, 발행 기록에 실패 원인만 남는다 — published_at은 null.")
    @Test
    void sendFailureKeepsConfirmationAndRecordsTheCause() throws Exception {
        // arrange
        doReturn(CompletableFuture.failedFuture(new TimeoutException("broker 응답 없음 (주입)")))
            .when(messageSendPort).send(anyString(), anyString(), anyString());

        // act
        Long orderId = confirmByCallback(2, "attempt-1");

        // assert
        EventOutbox row = await().atMost(WAIT).until(() -> outboxOf(orderId), r -> r.getLastError() != null);
        assertThat(row.getLastError()).contains("TimeoutException").contains("broker 응답 없음 (주입)");
        assertThat(row.getPublishedAt()).isNull();
        assertThat(orderStatusOf(orderId)).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(reservationsOf(orderId)).containsExactly("CONSUMED");
        assertThat(paymentOf(orderId).getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(PG.createCalls()).isEqualTo(1);
        assertThat(recordsOf(row.getEventId(), Duration.ofSeconds(3))).isEmpty();
    }

    /** 주문 → 확정 요청(202) → PG 수락 → PG 승인 → callback. callback이 결과 적용 경계를 지나 주문을 확정한다. */
    private Long confirmByCallback(int quantity, String key) throws Exception {
        Long orderId = createOrder(quantity);
        confirm(orderId, user, key);
        awaitPayment(orderId, PaymentStatus.PENDING);
        String transactionKey = paymentOf(orderId).getTransactionKey();
        PG.complete(transactionKey, "SUCCESS", null);
        assertThat(PG.sendCallback(transactionKey)).isEqualTo(200);
        return orderId;
    }

    private EventOutbox outboxOf(Long orderId) {
        return eventOutboxJpaRepository.findAll().stream()
            .filter(row -> row.getAggregateId().equals(orderId))
            .findFirst().orElseThrow();
    }

    private List<ConsumerRecord<String, String>> recordsOf(String eventId, Duration wait) {
        return KafkaTopicReader.read(KafkaTestContainersConfig.bootstrapServers(), orderEventsTopic,
            record -> record.value().contains("\"eventId\":\"" + eventId + "\""), 1, wait);
    }

    private void printFacts(String label, Long orderId, EventOutbox row, List<ConsumerRecord<String, String>> records) {
        Payment payment = paymentOf(orderId);
        EventOutbox latest = outboxOf(orderId);
        String broker = records.isEmpty() ? "record 없음"
            : "partition " + records.get(0).partition() + " · offset " + records.get(0).offset();
        System.out.printf("[E-4] %s | orderId=%d | providerOrderId=%s | transactionKey=%s | eventId=%s | payload=%s | 주문=%s | 예약=%s | published_at=%s | last_error=%s | broker=%s%n",
            label, orderId, payment.getProviderOrderId(), payment.getTransactionKey(), row.getEventId(), row.getPayload(),
            orderStatusOf(orderId), reservationsOf(orderId), latest.getPublishedAt(), latest.getLastError(), broker);
    }
}
