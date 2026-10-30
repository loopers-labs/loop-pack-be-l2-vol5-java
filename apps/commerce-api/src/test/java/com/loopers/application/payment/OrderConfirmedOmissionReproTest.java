package com.loopers.application.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.order.OrderModel;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.order.ReservationStatus;
import com.loopers.domain.payment.ProviderOrderId;
import com.loopers.infrastructure.order.KafkaOrderEventPublisher;
import com.loopers.support.kafka.BrokerRecords;
import com.loopers.support.kafka.RecordingOrderEventPublisher;
import com.loopers.support.pg.PaymentTestSupport;
import com.loopers.support.pg.TestPgStatus;
import com.loopers.support.pg.TestPgTransaction;
import com.loopers.testcontainers.KafkaBrokerFixture;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 주문 확정 뒤 직접 발행의 <b>누락 구간</b>을 재현하고 정상 발행 한 건과 나란히 남긴다 (W6 과제 2절). 다음 주(W7) Outbox 개선의 출발점이 되는 재현 코드다.
 *
 * <h3>두 사례의 역할</h3>
 * <ul>
 *   <li><b>사례 A (미발행)</b>: 실제 주문 API·로컬 PG fixture·실제 PG 결과 적용 경로(callback)로 확정한다. 주문 확정 트랜잭션이 commit된 뒤,
 *       send를 부르기 <b>직전</b>의 통제된 hook에서 발행을 중단한다. 주문 CONFIRMED·예약 소비는 남고, 그 eventId의 broker record는 없다.</li>
 *   <li><b>사례 B (정상 발행)</b>: 같은 경로로 확정하고 실제 Kafka broker로 발행한다. broker에는 B의 record만 있다(A가 남긴 것이 없다).
 *       이 record의 바이트는 commerce-streamer의 {@code ReproRecordConsumptionTest}가 실제 listener로 소비해 구매 활동으로 저장하는지 확인한다.</li>
 * </ul>
 *
 * <h3>재현 조건</h3>
 * <ul>
 *   <li>W6 직접 발행 경로: 커밋 {@code 389b6d4}(commit 뒤 {@code PaymentFacade.publishAfterCommit}). 이 테스트는 그 경로를 그대로 실행한다.</li>
 *   <li>고정 clock: {@value #FIXED_CONFIRMED_AT}. 품목: 상품 1개 × 수량 {@value PaymentScenarioTestBase#ORDER_QUANTITY}(단가 7,000원). 재고 5에서 시작.</li>
 *   <li>실행: {@code ./gradlew :apps:commerce-api:test --tests '*OrderConfirmedOmissionReproTest'} (Docker 필요: MySQL·Kafka 컨테이너)</li>
 * </ul>
 *
 * <p>주의: hook에서 발행을 건너뛰는 것은 "commit 뒤 send 전에 프로세스가 멈춘 상황"을 통제해 만든 <b>주입</b>이다. OS 강제 종료가 아니다.
 * W7의 Outbox 경로를 실행한 뒤 행을 지워 누락을 꾸미지 않았다. 이 테스트는 W6의 직접 발행 경로만 쓴다.
 */
class OrderConfirmedOmissionReproTest extends PaymentScenarioTestBase {

    static final String FIXED_CONFIRMED_AT = "2026-09-07T01:00:00Z";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 사례 A의 hook 시점(주문 commit 뒤, send 직전)에서 본 상태. */
    private record StateAtHook(OrderStatus orderStatus, Instant confirmedAt, ReservationStatus reservation, long brokerRecordsForOrder) {
    }

    @TestConfiguration
    static class FixedClockConfig {

        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse(FIXED_CONFIRMED_AT), ZoneOffset.UTC);
        }
    }

    @Autowired
    private RecordingOrderEventPublisher publisher;

    private static KafkaTemplate<Object, Object> realBrokerTemplate() {
        Map<String, Object> props = Map.of(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaBrokerFixture.bootstrapServers(),
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class,
            JsonSerializer.ADD_TYPE_INFO_HEADERS, false,
            ProducerConfig.ACKS_CONFIG, "all",
            ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true,
            ProducerConfig.MAX_BLOCK_MS_CONFIG, 10_000
        );
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(props));
    }

    /** 주문의 PG 결과를 실제 callback 경로로 적용해 확정한다. */
    private TestPgTransaction confirmThroughCallback(Long targetOrderId) throws Exception {
        confirm(targetOrderId, USER_ID, PaymentTestSupport.attemptKeyOf(targetOrderId), PaymentTestSupport.CARD_TYPE)
            .andExpect(status().isAccepted());
        PaymentTestSupport.completeAt(pg, targetOrderId, TestPgStatus.SUCCESS);
        TestPgTransaction transaction = PaymentTestSupport.onlyTransactionOf(pg, targetOrderId);
        callbackOf(transaction).andExpect(status().isOk()).andExpect(jsonPath("$.data.orderStatus").value("CONFIRMED"));
        return transaction;
    }

    @DisplayName("commit 뒤 send 직전에 중단한 주문은 확정된 채 broker record가 없고, 정상 발행한 주문은 broker에 record가 하나 있다.")
    @Test
    void confirmedOrderWithoutEvent_andPublishedOrderSideBySide() throws Exception {
        // arrange: 실제 broker의 격리 topic으로 보내는 발행기를 두고, 두 주문을 만든다
        String topic = KafkaBrokerFixture.createTopic("order-events-repro", 1);
        publisher.clear();
        publisher.delegateTo(new KafkaOrderEventPublisher(realBrokerTemplate(), topic, Duration.ofSeconds(10)));
        Long omittedOrderId = orderId;
        Long publishedOrderId = saveDraft(USER_ID);

        // act (사례 A): 주문 commit 뒤, send 직전 hook에서 그 시점의 상태를 관찰하고 발행을 중단한다
        AtomicReference<StateAtHook> atHook = new AtomicReference<>();
        publisher.beforeSend(event -> {
            OrderModel committed = orderJpaRepository.findById(event.orderId()).orElseThrow();
            ReservationStatus reservationAtHook = stockReservationJpaRepository.findByOrderId(event.orderId()).orElseThrow().getStatus();
            long recordsForOrder = BrokerRecords.readAll(topic).stream().filter(r -> r.key().equals(event.key())).count();
            atHook.set(new StateAtHook(committed.getStatus(), committed.getConfirmedAt(), reservationAtHook, recordsForOrder));
        });
        publisher.failWith("commit 뒤 send 직전에 중단(주입, OS 강제 종료 아님)");
        TestPgTransaction omittedTransaction = confirmThroughCallback(omittedOrderId);

        // assert (사례 A): hook 시점에 이미 commit돼 있었고 broker에는 아직 없었으며, 끝난 뒤에도 주문 사실만 남는다
        assertThat(atHook.get()).isEqualTo(new StateAtHook(
            OrderStatus.CONFIRMED, Instant.parse(FIXED_CONFIRMED_AT), ReservationStatus.CONSUMED, 0));
        assertThat(orderJpaRepository.findById(omittedOrderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(stockReservationJpaRepository.findByOrderId(omittedOrderId).orElseThrow().getStatus()).isEqualTo(ReservationStatus.CONSUMED);
        assertThat(stock()).as("A 확정으로 재고 2 차감").isEqualTo(INITIAL_STOCK - ORDER_QUANTITY);
        assertThat(pg.transactionsOf(ProviderOrderId.from(omittedOrderId))).as("PG create는 한 번뿐").hasSize(1);
        assertThat(publisher.attempts()).extracting(e -> e.orderId()).containsExactly(omittedOrderId);
        assertThat(publisher.events()).as("broker가 받은 이벤트 없음").isEmpty();
        assertThat(BrokerRecords.readAll(topic)).as("A의 eventId record는 broker에 없다").isEmpty();

        // act (사례 B): 같은 경로로 확정하고 이번에는 실제 broker로 발행한다
        publisher.beforeSend(null);
        publisher.failWith(null);
        TestPgTransaction publishedTransaction = confirmThroughCallback(publishedOrderId);

        // assert (사례 B): broker에는 B의 record 하나뿐이다. A가 남긴 record가 없으므로 offset은 0이다
        List<BrokerRecords.Record> records = BrokerRecords.readAll(topic);
        assertThat(records).hasSize(1);
        BrokerRecords.Record record = records.get(0);
        assertThat(record.key()).isEqualTo(String.valueOf(publishedOrderId));
        assertThat(record.offset()).isZero();
        JsonNode body = MAPPER.readTree(record.value());
        assertThat(body.get("eventType").asText()).isEqualTo("OrderConfirmed");
        assertThat(body.get("schemaVersion").asInt()).isEqualTo(1);
        assertThat(body.get("eventId").asText()).isEqualTo("order-confirmed:v1:" + publishedOrderId);
        assertThat(body.get("orderId").asLong()).isEqualTo(publishedOrderId);
        assertThat(body.get("confirmedAt").asText()).isEqualTo(FIXED_CONFIRMED_AT);
        assertThat(body.get("items")).hasSize(1);
        assertThat(body.get("items").get(0).get("productId").asLong()).isEqualTo(product.getId());
        assertThat(body.get("items").get(0).get("quantity").asInt()).isEqualTo(ORDER_QUANTITY);
        assertThat(orderJpaRepository.findById(publishedOrderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(stockReservationJpaRepository.findByOrderId(publishedOrderId).orElseThrow().getStatus()).isEqualTo(ReservationStatus.CONSUMED);

        // 관찰 결과를 남긴다 (카드·비밀 값은 담지 않는다). docs/week6/omission-repro.md에 이 실행의 값을 옮겨 적는다
        print("A (미발행)", omittedOrderId, omittedTransaction, "broker record 없음(hook에서 중단)", atHook.get());
        print("B (정상 발행)", publishedOrderId, publishedTransaction,
            "topic=" + topic + ", partition=" + record.partition() + ", offset=" + record.offset() + ", key=" + record.key(), null);
        System.out.println("[누락 재현] B의 broker record 값(streamer 소비 확인용): " + record.value());
    }

    private void print(String label, Long id, TestPgTransaction transaction, String broker, StateAtHook hook) {
        OrderModel order = orderJpaRepository.findById(id).orElseThrow();
        System.out.println("[누락 재현] " + label
            + " | orderId=" + id
            + " | providerOrderId=" + ProviderOrderId.from(id)
            + " | transactionKey=" + transaction.transactionKey()
            + " | eventId=order-confirmed:v1:" + id
            + " | confirmedAt=" + order.getConfirmedAt()
            + " | items=[productId=" + product.getId() + ", quantity=" + ORDER_QUANTITY + "]"
            + " | 주문=" + order.getStatus()
            + " | 예약=" + stockReservationJpaRepository.findByOrderId(id).orElseThrow().getStatus()
            + " | broker=" + broker
            + (hook == null ? "" : " | hook 시점 상태=" + hook));
    }
}
