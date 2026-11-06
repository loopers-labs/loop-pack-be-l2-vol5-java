package com.loopers.payment.scenario;

import com.loopers.order.domain.OrderStatus;
import com.loopers.outbox.adapter.out.persistence.OrderOutboxJpaRepository;
import com.loopers.outbox.application.port.in.OrderOutboxRelayUseCase;
import com.loopers.outbox.domain.OrderOutbox;
import com.loopers.outbox.domain.OrderOutboxStatus;
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
import org.springframework.kafka.KafkaException;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;

/**
 * S-13 (W6 E-4 → W7) — 확정 commit 뒤 발행 전에 멈추는 구간을 실제 API·로컬 PG callback으로 다시 본다.
 * <p>
 * W6에서는 commit 직후 listener의 send를 건너뛰면 주문은 확정인데 이벤트가 사라졌다(docs/week6/incident-w6.md E-4).
 * W7에서는 확정 TX가 order_outbox 행만 남기고, Kafka로 보내는 것은 relay다. 그래서 같은 장면에서 "relay가 아직 돌지 않음"이 곧
 * "commit 뒤 발행 전 중단"이고(C-5 첫 번째 중단 지점), 행이 PENDING으로 남아 있다가 relay가 같은 eventId로 보낸다.
 * relay의 send가 실패해도 주문·결제·예약은 되돌아가지 않고 PG를 다시 부르지 않는다.
 * <p>
 * 이 클래스에서는 scheduler가 없다(4단계 전) — relay는 테스트가 직접 부른다. 실행 사실은 표준 출력의 [S-13] 줄로 남긴다.
 */
class S13PublishGapScenarioTest extends PaymentScenarioSupport {

    @Autowired
    private OrderOutboxJpaRepository orderOutboxJpaRepository;

    @Autowired
    private OrderOutboxRelayUseCase relay;

    @Value("${kafka.topics.order-events}")
    private String orderEventsTopic;

    @DisplayName("S-13 C-5 확정 commit 뒤 relay가 돌기 전에는 주문 CONFIRMED·예약 CONSUMED이고 행은 PENDING, record는 없다. relay가 돌면 같은 eventId·payload로 1건 나가고 PUBLISHED가 된다 — PG는 다시 부르지 않는다.")
    @Test
    void confirmedOrderWaitsAsPendingUntilRelaySendsIt() throws Exception {
        // arrange: 실제 결제·callback 경로로 확정 (relay는 아직 안 돎 = commit 뒤 발행 전 중단)
        Long orderId = confirmByCallback(2, "attempt-a");
        int createCalls = PG.createCalls();

        // assert: 확정·예약 소비는 끝났고, 보낼 사실은 PENDING으로 남아 있다. 아직 broker에는 없다
        OrderOutbox waiting = outboxOf(orderId);
        assertThat(orderStatusOf(orderId)).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(reservationsOf(orderId)).containsExactly("CONSUMED");
        assertThat(waiting.getStatus()).isEqualTo(OrderOutboxStatus.PENDING);
        assertThat(recordsOf(waiting.getEventId(), Duration.ofSeconds(3))).isEmpty();

        // act: relay 한 회차
        relay.relayOnce();

        // assert: 저장된 원문 그대로 1건, 행은 broker가 알려 준 위치로 PUBLISHED. 주문·PG는 그대로
        List<ConsumerRecord<String, String>> records = recordsOf(waiting.getEventId(), Duration.ofSeconds(10));
        OrderOutbox sent = outboxOf(orderId);
        assertThat(records).singleElement().satisfies(record -> {
            assertThat(record.key()).isEqualTo(String.valueOf(orderId));
            assertThat(record.value()).isEqualTo(waiting.getPayload());
        });
        assertThat(sent.getStatus()).isEqualTo(OrderOutboxStatus.PUBLISHED);
        assertThat(sent.getPublishedPartition()).isEqualTo(records.get(0).partition());
        assertThat(sent.getPublishedOffset()).isEqualTo(records.get(0).offset());
        assertThat(orderStatusOf(orderId)).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(PG.createCalls()).isEqualTo(createCalls);

        printFacts("relay 전 PENDING → relay 뒤 PUBLISHED", orderId, sent, records);
    }

    @DisplayName("S-13 relay의 send가 실패하면(broker 응답 없음 주입) 행은 PENDING·시도 1·원인으로 남고, 주문 CONFIRMED·결제 SUCCESS·예약 CONSUMED는 그대로이며 PG를 다시 부르지 않는다.")
    @Test
    void sendFailureKeepsConfirmationAndWaitsForRetry() throws Exception {
        // arrange
        doReturn(CompletableFuture.failedFuture(new TimeoutException("broker 응답 없음 (주입)")))
            .when(messageSendPort).send(anyString(), anyString(), anyString());
        Long orderId = confirmByCallback(2, "attempt-1");

        // act
        relay.relayOnce();

        // assert
        OrderOutbox row = outboxOf(orderId);
        assertThat(row.getStatus()).isEqualTo(OrderOutboxStatus.PENDING);
        assertThat(row.getAttemptCount()).isEqualTo(1);
        assertThat(row.getLastError()).contains("TimeoutException").contains("broker 응답 없음 (주입)");
        assertThat(row.getPublishedAt()).isNull();
        assertThat(orderStatusOf(orderId)).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(reservationsOf(orderId)).containsExactly("CONSUMED");
        assertThat(paymentOf(orderId).getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(PG.createCalls()).isEqualTo(1);
        assertThat(recordsOf(row.getEventId(), Duration.ofSeconds(3))).isEmpty();
    }

    @DisplayName("S-13 send 호출이 바로 예외를 던져도(broker 전부 장애 → metadata 대기가 max.block.ms를 넘김) 행은 PENDING·시도 1·원인으로 남고 주문 CONFIRMED, PG 재호출 없음.")
    @Test
    void synchronousSendFailureAlsoWaitsForRetry() throws Exception {
        // arrange: KafkaTemplate은 metadata timeout을 future가 아니라 send() 호출의 예외로 던진다
        doThrow(new KafkaException("Send failed", new TimeoutException("Topic order-events not present in metadata after 1000 ms. (주입)")))
            .when(messageSendPort).send(anyString(), anyString(), anyString());
        Long orderId = confirmByCallback(2, "attempt-1");

        // act
        relay.relayOnce();

        // assert
        OrderOutbox row = outboxOf(orderId);
        assertThat(row.getStatus()).isEqualTo(OrderOutboxStatus.PENDING);
        assertThat(row.getAttemptCount()).isEqualTo(1);
        assertThat(row.getLastError()).contains("TimeoutException").contains("not present in metadata");
        assertThat(orderStatusOf(orderId)).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(PG.createCalls()).isEqualTo(1);
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

    private OrderOutbox outboxOf(Long orderId) {
        return orderOutboxJpaRepository.findAll().stream()
            .filter(row -> row.getOrderId().equals(orderId))
            .findFirst().orElseThrow();
    }

    private List<ConsumerRecord<String, String>> recordsOf(String eventId, Duration wait) {
        return KafkaTopicReader.read(KafkaTestContainersConfig.bootstrapServers(), orderEventsTopic,
            record -> record.value().contains("\"eventId\":\"" + eventId + "\""), 1, wait);
    }

    private void printFacts(String label, Long orderId, OrderOutbox row, List<ConsumerRecord<String, String>> records) {
        Payment payment = paymentOf(orderId);
        String broker = records.isEmpty() ? "record 없음"
            : "partition " + records.get(0).partition() + " · offset " + records.get(0).offset();
        System.out.printf("[S-13] %s | orderId=%d | providerOrderId=%s | transactionKey=%s | eventId=%s | payload=%s | 주문=%s | 예약=%s | status=%s | broker=%s%n",
            label, orderId, payment.getProviderOrderId(), payment.getTransactionKey(), row.getEventId(), row.getPayload(),
            orderStatusOf(orderId), reservationsOf(orderId), row.getStatus(), broker);
    }
}
