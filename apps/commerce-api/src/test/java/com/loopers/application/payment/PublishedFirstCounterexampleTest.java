package com.loopers.application.payment;

import com.loopers.application.order.OrderOutboxRelay;
import com.loopers.application.order.OrderOutboxRelayProperties;
import com.loopers.domain.order.OrderConfirmedEvent;
import com.loopers.domain.order.OrderOutboxModel;
import com.loopers.domain.order.OrderOutboxSender;
import com.loopers.domain.order.OrderOutboxService;
import com.loopers.domain.order.OrderOutboxStatus;
import com.loopers.support.kafka.BrokerRecords;
import com.loopers.support.kafka.OutboxTestSenders;
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
 * 반례: "PUBLISHED를 먼저 저장하면 중복을 줄일 수 있다"는 제안이 만드는 누락 (W7 과제 8절, design.md 4-5절).
 *
 * <p>제안한 순서(시도 기록 → PUBLISHED 저장 → send)는 이 테스트 안에서만 서비스 단계를 직접 불러 조립한다. 제품 relay는 broker 확인 뒤에만
 * PUBLISHED를 저장한다. 두 순서를 같은 지점, 즉 "DB 기록과 broker 사이"에서 멈춰 비교한다. 멈춤은 다음 단계를 부르지 않는 것으로 만든다
 * (OS 강제 종료가 아니다). 실제 MySQL·broker를 쓰고, 테스트는 Outbox 상태를 직접 고치지 않는다.
 */
class PublishedFirstCounterexampleTest extends PaymentScenarioTestBase {

    private static final OrderOutboxRelayProperties PROPERTIES =
        new OrderOutboxRelayProperties(false, Duration.ofSeconds(1), 50, Duration.ofSeconds(1), 2.0, Duration.ofMinutes(5), 5);

    @Autowired
    private OrderOutboxService orderOutboxService;

    private static OrderOutboxRelay relay(OrderOutboxService stages, OrderOutboxSender sender, Instant now) {
        return new OrderOutboxRelay(stages, sender, PROPERTIES, Clock.fixed(now, ZoneOffset.UTC));
    }

    private OrderOutboxModel rowOf(Long targetOrderId) {
        return orderOutboxJpaRepository.findByEventId(OrderConfirmedEvent.eventIdOf(targetOrderId)).orElseThrow();
    }

    private static long recordsFor(String topic, Long targetOrderId) {
        return BrokerRecords.readAll(topic).stream().filter(record -> record.key().equals(String.valueOf(targetOrderId))).count();
    }

    @DisplayName("PUBLISHED를 먼저 저장하고 send 전에 멈추면 broker에는 아무것도 없는데 행은 PUBLISHED라, 재시작한 relay도 backlog도 그 사건을 다시 보지 못한다(영구 누락). "
        + "broker 확인 뒤 저장하는 순서는 같은 지점에서 멈춰도 다시 보낸다(중복은 생기지만 누락은 없다).")
    @Test
    void publishedFirstLosesEvent_whileBrokerAckFirstResends() throws Exception {
        // arrange: 두 주문을 확정해 Outbox PENDING 두 행을 만든다
        String topic = KafkaBrokerFixture.createTopic("order-outbox-counterexample", 1);
        Long publishedFirstOrderId = orderId;
        Long ackFirstOrderId = saveDraft(USER_ID);
        confirmOrderThroughCallback(publishedFirstOrderId);
        confirmOrderThroughCallback(ackFirstOrderId);
        String publishedFirstEventId = OrderConfirmedEvent.eventIdOf(publishedFirstOrderId);
        Instant stoppedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        // act (제안한 순서): 시도 기록 → PUBLISHED 저장 → [멈춤: send를 부르지 않는다]
        assertThat(orderOutboxService.startAttempt(publishedFirstEventId)).isPresent();
        assertThat(orderOutboxService.markPublished(publishedFirstEventId, stoppedAt)).isTrue();

        // act (W7 순서): 시도 기록 → send → broker 확인 → [멈춤: PUBLISHED를 저장하지 않는다]
        OrderOutboxRelay stoppedRelay = relay(
            RelayStopHook.before(RelayStopHook.Stage.MARK_PUBLISHED, orderOutboxService), OutboxTestSenders.toBroker(topic), stoppedAt);
        assertThatThrownBy(stoppedRelay::runOnce).isInstanceOf(RelayStopHook.RelayStopped.class);

        // assert: 멈춘 직후. 제안한 순서의 행은 "보냈다"고 적혀 있지만 broker에는 없다
        assertThat(rowOf(publishedFirstOrderId).getStatus()).isEqualTo(OrderOutboxStatus.PUBLISHED);
        assertThat(recordsFor(topic, publishedFirstOrderId)).isZero();
        assertThat(rowOf(ackFirstOrderId).getStatus()).isEqualTo(OrderOutboxStatus.PENDING);
        assertThat(recordsFor(topic, ackFirstOrderId)).isEqualTo(1);
        assertThat(orderOutboxService.backlog().pendingCount()).as("누락된 사건은 backlog에도 보이지 않는다").isEqualTo(1);

        // act: 재시작. 새 relay 객체·새 producer, 같은 DB·topic. 충분히 시간이 지난 뒤에도 다시 돌린다
        OrderOutboxRelay.Run restarted = relay(orderOutboxService, OutboxTestSenders.toBroker(topic), stoppedAt.plusSeconds(60)).runOnce();
        OrderOutboxRelay.Run later = relay(orderOutboxService, OutboxTestSenders.toBroker(topic), stoppedAt.plus(Duration.ofHours(1))).runOnce();

        // assert: W7 순서는 같은 사건을 다시 보내 broker에 2개(중복, consumer가 흡수). 제안한 순서는 끝까지 0개(누락)
        assertThat(restarted).isEqualTo(new OrderOutboxRelay.Run(true, 1, 1, 0));
        assertThat(later).isEqualTo(new OrderOutboxRelay.Run(true, 0, 0, 0));
        assertThat(recordsFor(topic, ackFirstOrderId)).isEqualTo(2);
        assertThat(recordsFor(topic, publishedFirstOrderId)).as("한 번도 broker에 쓰이지 않았고 다시 보낼 근거도 없다").isZero();
        assertThat(List.of(rowOf(publishedFirstOrderId), rowOf(ackFirstOrderId)))
            .extracting(OrderOutboxModel::getStatus).containsOnly(OrderOutboxStatus.PUBLISHED);
        assertThat(orderOutboxService.backlog().pendingCount()).as("모두 보낸 것처럼 보인다").isZero();
    }
}
