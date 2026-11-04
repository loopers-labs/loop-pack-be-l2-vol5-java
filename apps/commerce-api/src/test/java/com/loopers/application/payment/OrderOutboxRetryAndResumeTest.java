package com.loopers.application.payment;

import com.loopers.application.order.OrderOutboxRelay;
import com.loopers.application.order.OrderOutboxRelayProperties;
import com.loopers.domain.order.OrderConfirmedEvent;
import com.loopers.domain.order.OrderOutboxBacklog;
import com.loopers.domain.order.OrderOutboxModel;
import com.loopers.domain.order.OrderOutboxSender;
import com.loopers.domain.order.OrderOutboxService;
import com.loopers.domain.order.OrderOutboxStatus;
import com.loopers.support.kafka.BrokerRecords;
import com.loopers.support.kafka.OutboxTestSenders;
import com.loopers.testcontainers.KafkaBrokerFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * broker 장애 뒤의 재처리, 반복 실패의 노출, eventId를 지정한 운영 재개, backlog 관찰을 확인한다 (W7 과제 4절, design.md 4-1절).
 * 시각은 고정 clock으로 통제하고, broker 장애는 닿지 않는 주소의 발행기로 만든다. 행은 어느 장면에서도 지우지 않는다.
 */
@ExtendWith(OutputCaptureExtension.class)
class OrderOutboxRetryAndResumeTest extends PaymentScenarioTestBase {

    private static final OrderOutboxRelayProperties PROPERTIES =
        new OrderOutboxRelayProperties(false, Duration.ofSeconds(1), 50, Duration.ofSeconds(1), 2.0, Duration.ofSeconds(4), 3);

    @Autowired
    private OrderOutboxService orderOutboxService;

    private OrderOutboxRelay relay(OrderOutboxSender sender, Instant now) {
        return new OrderOutboxRelay(orderOutboxService, sender, PROPERTIES, Clock.fixed(now, ZoneOffset.UTC));
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }

    private OrderOutboxModel rowOf(Long targetOrderId) {
        return orderOutboxJpaRepository.findByEventId(OrderConfirmedEvent.eventIdOf(targetOrderId)).orElseThrow();
    }

    @DisplayName("broker 장애 동안 쌓인 PENDING은 장애가 풀리면 같은 행·같은 사건으로 다시 보내고, backlog가 비워진다.")
    @Test
    void sendsAccumulatedPending_afterBrokerRecovers() throws Exception {
        // arrange
        String topic = KafkaBrokerFixture.createTopic("order-outbox-retry", 1);
        Long secondOrderId = saveDraft(USER_ID);
        confirmOrderThroughCallback(orderId);
        confirmOrderThroughCallback(secondOrderId);
        Instant outageAt = now();

        // act: 장애 중의 회차
        OrderOutboxRelay.Run duringOutage = relay(OutboxTestSenders.unreachable(topic), outageAt).runOnce();

        // assert
        assertThat(duringOutage).isEqualTo(new OrderOutboxRelay.Run(true, 2, 0, 2));
        OrderOutboxBacklog backlog = relay(OutboxTestSenders.unreachable(topic), outageAt).backlog();
        assertThat(backlog.pendingCount()).isEqualTo(2);
        assertThat(backlog.maxAttemptCount()).isEqualTo(1);
        assertThat(backlog.oldestPendingCreatedAt()).isNotNull();

        // act: 장애가 풀린 뒤 다음 시도 시각의 회차
        OrderOutboxRelay.Run recovered = relay(OutboxTestSenders.toBroker(topic), outageAt.plus(PROPERTIES.initialBackoff())).runOnce();

        // assert
        assertThat(recovered).isEqualTo(new OrderOutboxRelay.Run(true, 2, 2, 0));
        assertThat(relay(OutboxTestSenders.toBroker(topic), outageAt).backlog()).isEqualTo(new OrderOutboxBacklog(0, null, 0));
        assertThat(BrokerRecords.readAll(topic)).extracting(BrokerRecords.Record::key)
            .containsExactlyInAnyOrder(String.valueOf(orderId), String.valueOf(secondOrderId));
        assertThat(List.of(rowOf(orderId), rowOf(secondOrderId)))
            .allSatisfy(row -> {
                assertThat(row.getStatus()).isEqualTo(OrderOutboxStatus.PUBLISHED);
                assertThat(row.getAttemptCount()).isEqualTo(2);
            });
    }

    @DisplayName("같은 원인으로 계속 실패하면 대기 시간이 상한까지 늘고, 알림 기준 시도부터 원인과 eventId를 ERROR로 남긴다. 행은 PENDING으로 남는다.")
    @Test
    void backsOffToMax_andExposesRepeatedFailure(CapturedOutput output) throws Exception {
        // arrange
        String topic = KafkaBrokerFixture.createTopic("order-outbox-retry", 1);
        confirmOrderThroughCallback(orderId);
        String eventId = OrderConfirmedEvent.eventIdOf(orderId);
        Instant at = now();
        List<Duration> waits = new ArrayList<>();

        // act: 매번 다음 시도 시각에 회차를 돌리고, 그때마다 broker에 닿지 않는다
        for (int attempt = 1; attempt <= 4; attempt++) {
            relay(OutboxTestSenders.unreachable(topic), at).runOnce();
            Instant next = rowOf(orderId).getNextAttemptAt();
            waits.add(Duration.between(at, next));
            at = next;
        }

        // assert
        assertThat(waits).as("1s → 2s → 4s(상한) → 4s").containsExactly(
            Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4), Duration.ofSeconds(4)
        );
        OrderOutboxModel row = rowOf(orderId);
        assertThat(row.getStatus()).isEqualTo(OrderOutboxStatus.PENDING);
        assertThat(row.getAttemptCount()).isEqualTo(4);
        assertThat(row.getLastError()).isNotBlank();
        assertThat(output.getOut()).contains("Outbox 발행이 반복해서 실패한다")
            .contains("eventId=" + eventId + ", orderId=" + orderId + ", attempt=3")
            .contains("eventId=" + eventId + ", orderId=" + orderId + ", attempt=4");
        assertThat(output.getOut()).as("알림 기준 전의 실패는 ERROR가 아니다")
            .doesNotContain("반복해서 실패한다(원인 확인 필요): eventId=" + eventId + ", orderId=" + orderId + ", attempt=2,");
    }

    @DisplayName("재개는 eventId로 지정한 PENDING 한 건을 다음 시도 시각을 기다리지 않고 지금 보낸다. 이미 PUBLISHED이거나 없는 eventId는 보내지 않는다.")
    @Test
    void resumesOnePendingNow_byEventId() throws Exception {
        // arrange: 장애로 실패해 다음 시도 시각이 미래로 밀린 행
        String topic = KafkaBrokerFixture.createTopic("order-outbox-retry", 1);
        confirmOrderThroughCallback(orderId);
        String eventId = OrderConfirmedEvent.eventIdOf(orderId);
        Instant failedAt = now();
        relay(OutboxTestSenders.unreachable(topic), failedAt).runOnce();
        assertThat(rowOf(orderId).getNextAttemptAt()).isAfter(failedAt);

        // act: 원인을 고친 뒤, 같은 시각에 eventId를 지정해 재개한다
        OrderOutboxRelay relay = relay(OutboxTestSenders.toBroker(topic), failedAt);
        OrderOutboxRelay.Run resumed = relay.resume(eventId);
        OrderOutboxRelay.Run again = relay.resume(eventId);
        OrderOutboxRelay.Run unknown = relay.resume(OrderConfirmedEvent.eventIdOf(999_999L));

        // assert
        assertThat(resumed).isEqualTo(new OrderOutboxRelay.Run(true, 1, 1, 0));
        assertThat(again).as("PUBLISHED는 다시 보내지 않는다").isEqualTo(new OrderOutboxRelay.Run(true, 0, 0, 0));
        assertThat(unknown).isEqualTo(new OrderOutboxRelay.Run(true, 0, 0, 0));
        assertThat(rowOf(orderId).getStatus()).isEqualTo(OrderOutboxStatus.PUBLISHED);
        assertThat(rowOf(orderId).getAttemptCount()).isEqualTo(2);
        assertThat(BrokerRecords.readAll(topic)).hasSize(1);
    }
}
