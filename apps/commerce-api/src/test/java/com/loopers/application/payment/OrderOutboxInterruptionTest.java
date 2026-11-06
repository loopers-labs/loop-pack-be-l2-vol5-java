package com.loopers.application.payment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.order.OrderOutboxRelay;
import com.loopers.application.order.OrderOutboxRelayProperties;
import com.loopers.domain.order.OrderConfirmedEvent;
import com.loopers.domain.order.OrderEventPublication;
import com.loopers.domain.order.OrderOutboxModel;
import com.loopers.domain.order.OrderOutboxSender;
import com.loopers.domain.order.OrderOutboxService;
import com.loopers.domain.order.OrderOutboxStatus;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.order.ReservationStatus;
import com.loopers.support.kafka.BrokerRecords;
import com.loopers.support.kafka.OutboxTestSenders;
import com.loopers.support.outbox.RecordingOutboxSender;
import com.loopers.support.outbox.RelayStopHook;
import com.loopers.testcontainers.KafkaBrokerFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 두 중단 구간을 실제 MySQL·실제 broker로 확인한다 (W7 과제 6절, design.md 4-2절).
 *
 * <ul>
 *   <li>확정 commit 뒤 relay 실행 전 중단 → 새로 만든 relay가 저장된 행에서 사건을 찾아 보낸다.</li>
 *   <li>broker 확인 뒤 PUBLISHED 저장 전 중단 → 같은 행이 PENDING으로 남고, 새로 만든 relay가 같은 사건을 다시 보낸다.</li>
 * </ul>
 *
 * <p>중단은 {@link RelayStopHook}이 만든다. 앞 단계는 실제로 끝내고 다음 단계만 막아 relay의 회차 흐름을 끝낸다(예외로 끝낸 흐름이지 OS 강제 종료가 아니다).
 * 재시작은 새 relay 객체·새 producer로 하고 같은 DB·topic·eventId를 그대로 쓴다. 테스트는 Outbox 상태를 직접 고치지 않는다.
 * 소비 쪽(구매 활동)은 이 테스트의 범위가 아니다. broker에 record가 없으면 구매 활동을 만들 것도 없다는 것까지만 본다.
 */
class OrderOutboxInterruptionTest extends PaymentScenarioTestBase {

    private static final OrderOutboxRelayProperties PROPERTIES =
        new OrderOutboxRelayProperties(false, Duration.ofSeconds(1), 50, Duration.ofSeconds(1), 2.0, Duration.ofMinutes(5), 5);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private OrderOutboxService orderOutboxService;

    private static OrderOutboxRelay relay(OrderOutboxService stages, OrderOutboxSender sender, Instant now) {
        return new OrderOutboxRelay(stages, sender, PROPERTIES, Clock.fixed(now, ZoneOffset.UTC));
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }

    private OrderOutboxModel onlyRow() {
        List<OrderOutboxModel> rows = outboxRows();
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    private static OrderEventPublication.Sent onlySent(RecordingOutboxSender sender) {
        List<OrderEventPublication> results = sender.results();
        assertThat(results).hasSize(1);
        assertThat(results.get(0)).as("broker가 받았다는 확인").isInstanceOf(OrderEventPublication.Sent.class);
        return (OrderEventPublication.Sent) results.get(0);
    }

    private static String eventIdIn(BrokerRecords.Record record) {
        try {
            return MAPPER.readTree(record.value()).get("eventId").asText();
        } catch (JsonProcessingException e) {
            throw new AssertionError("broker record 값이 JSON이 아닙니다: " + record.value(), e);
        }
    }

    @DisplayName("확정 commit 뒤 relay 실행 전에 멈추면 확정 주문과 PENDING Outbox만 남고 broker에는 record가 없다. 새로 만든 relay가 저장된 행에서 같은 사건을 찾아 보낸다.")
    @Test
    void stopAfterCommitBeforeRelay_thenRestartedRelayPublishesFromStoredRow() throws Exception {
        // arrange: 실제 API·PG fixture·callback으로 확정을 commit한다
        String topic = KafkaBrokerFixture.createTopic("order-outbox-interruption", 1);
        confirmOrderThroughCallback(orderId);
        String eventId = OrderConfirmedEvent.eventIdOf(orderId);
        OrderOutboxModel recorded = onlyRow();
        RelayStopHook hook = RelayStopHook.before(RelayStopHook.Stage.SELECT_DUE, orderOutboxService);
        RecordingOutboxSender firstSender = RecordingOutboxSender.of(OutboxTestSenders.toBroker(topic));
        OrderOutboxRelay stoppedRelay = relay(hook, firstSender, now());

        // act: relay가 보낼 행을 고르기 전에 멈춘다
        assertThatThrownBy(stoppedRelay::runOnce).isInstanceOf(RelayStopHook.RelayStopped.class);

        // assert: 확정 사실과 보낼 사실은 commit돼 있고, 아무것도 보내지 않았다
        assertThat(hook.stops()).hasSize(1);
        assertThat(firstSender.results()).as("send를 한 번도 부르지 않았다").isEmpty();
        assertThat(order().status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(reservation()).isEqualTo(ReservationStatus.CONSUMED);
        OrderOutboxModel pending = onlyRow();
        assertThat(pending.getEventId()).isEqualTo(eventId);
        assertThat(pending.getStatus()).isEqualTo(OrderOutboxStatus.PENDING);
        assertThat(pending.getAttemptCount()).isZero();
        assertThat(BrokerRecords.readAll(topic)).as("구매 활동을 만들 record가 아직 없다").isEmpty();

        // act: 재시작. 새 relay 객체·새 producer, 같은 DB·topic
        RecordingOutboxSender restartedSender = RecordingOutboxSender.of(OutboxTestSenders.toBroker(topic));
        Instant restartedAt = now();
        OrderOutboxRelay.Run run = relay(orderOutboxService, restartedSender, restartedAt).runOnce();

        // assert: 저장된 행의 key·payload 그대로 한 번 보내고 같은 행을 PUBLISHED로 바꾼다
        assertThat(run).isEqualTo(new OrderOutboxRelay.Run(true, 1, 1, 0));
        OrderEventPublication.Sent ack = onlySent(restartedSender);
        List<BrokerRecords.Record> records = BrokerRecords.readAll(topic);
        assertThat(records).extracting(BrokerRecords.Record::offset).containsExactly(ack.offset());
        assertThat(records.get(0).key()).isEqualTo(String.valueOf(orderId));
        assertThat(records.get(0).value()).isEqualTo(recorded.getPayload());
        assertThat(eventIdIn(records.get(0))).isEqualTo(eventId);
        OrderOutboxModel published = onlyRow();
        assertThat(published.getId()).isEqualTo(recorded.getId());
        assertThat(published.getStatus()).isEqualTo(OrderOutboxStatus.PUBLISHED);
        assertThat(published.getAttemptCount()).isEqualTo(1);
        assertThat(published.getPublishedAt()).isEqualTo(restartedAt);
    }

    @DisplayName("broker 확인 뒤 PUBLISHED 저장 전에 멈추면 같은 Outbox 행이 PENDING으로 남는다. 새로 만든 relay가 같은 사건을 다시 보내 broker에 같은 eventId가 다른 offset으로 두 번 기록된다.")
    @Test
    void stopAfterBrokerAckBeforePublishedSave_thenRestartedRelayRepublishesSameEvent() throws Exception {
        // arrange
        String topic = KafkaBrokerFixture.createTopic("order-outbox-interruption", 1);
        confirmOrderThroughCallback(orderId);
        String eventId = OrderConfirmedEvent.eventIdOf(orderId);
        OrderOutboxModel recorded = onlyRow();
        RelayStopHook hook = RelayStopHook.before(RelayStopHook.Stage.MARK_PUBLISHED, orderOutboxService);
        RecordingOutboxSender firstSender = RecordingOutboxSender.of(OutboxTestSenders.toBroker(topic));
        OrderOutboxRelay stoppedRelay = relay(hook, firstSender, now());

        // act: 시도 기록 → send → broker 확인까지 끝내고, PUBLISHED 저장 직전에 멈춘다
        assertThatThrownBy(stoppedRelay::runOnce).isInstanceOf(RelayStopHook.RelayStopped.class);

        // assert: 실제 send 성공 metadata가 있고 broker에 그 위치로 기록돼 있다
        assertThat(hook.stops()).containsExactly(RelayStopHook.Stage.MARK_PUBLISHED + " eventId=" + eventId);
        OrderEventPublication.Sent firstAck = onlySent(firstSender);
        assertThat(firstAck.topic()).isEqualTo(topic);
        List<BrokerRecords.Record> afterStop = BrokerRecords.readAll(topic);
        assertThat(afterStop).extracting(BrokerRecords.Record::offset).containsExactly(firstAck.offset());
        assertThat(afterStop.get(0).value()).isEqualTo(recorded.getPayload());

        // assert: 같은 행이 PENDING으로 남는다. 시도는 기록됐지만 PUBLISHED도 실패도 기록되지 않았다
        OrderOutboxModel pending = onlyRow();
        assertThat(pending.getId()).isEqualTo(recorded.getId());
        assertThat(pending.getStatus()).isEqualTo(OrderOutboxStatus.PENDING);
        assertThat(pending.getAttemptCount()).isEqualTo(1);
        assertThat(pending.getPublishedAt()).isNull();
        assertThat(pending.getLastError()).isNull();
        assertThat(pending.getNextAttemptAt()).as("실패로 기록하지 않아 다음 시도 시각이 그대로다").isEqualTo(recorded.getNextAttemptAt());

        // act: 재시작. 새 relay 객체·새 producer, 같은 DB·topic
        RecordingOutboxSender restartedSender = RecordingOutboxSender.of(OutboxTestSenders.toBroker(topic));
        Instant restartedAt = now();
        OrderOutboxRelay.Run run = relay(orderOutboxService, restartedSender, restartedAt).runOnce();

        // assert: 같은 사건을 다시 보내 broker에는 두 record가 다른 offset에 있고, 둘 다 같은 eventId·같은 payload다
        assertThat(run).isEqualTo(new OrderOutboxRelay.Run(true, 1, 1, 0));
        OrderEventPublication.Sent secondAck = onlySent(restartedSender);
        assertThat(secondAck.offset()).as("재발행은 새 offset에 기록된다").isGreaterThan(firstAck.offset());
        List<BrokerRecords.Record> records = BrokerRecords.readAll(topic);
        assertThat(records).extracting(BrokerRecords.Record::offset).containsExactly(firstAck.offset(), secondAck.offset());
        assertThat(records).allSatisfy(record -> {
            assertThat(record.key()).isEqualTo(String.valueOf(orderId));
            assertThat(record.value()).isEqualTo(recorded.getPayload());
            assertThat(eventIdIn(record)).isEqualTo(eventId);
        });

        // assert: Outbox는 여전히 같은 한 행이고, 이번에는 PUBLISHED로 끝난다
        OrderOutboxModel published = onlyRow();
        assertThat(published.getId()).isEqualTo(recorded.getId());
        assertThat(published.getStatus()).isEqualTo(OrderOutboxStatus.PUBLISHED);
        assertThat(published.getAttemptCount()).isEqualTo(2);
        assertThat(published.getPublishedAt()).isEqualTo(restartedAt);
    }
}
