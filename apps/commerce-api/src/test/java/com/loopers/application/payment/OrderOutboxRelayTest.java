package com.loopers.application.payment;

import com.loopers.application.order.OrderOutboxRelay;
import com.loopers.application.order.OrderOutboxRelayProperties;
import com.loopers.domain.order.OrderOutboxModel;
import com.loopers.domain.order.OrderOutboxSender;
import com.loopers.domain.order.OrderOutboxService;
import com.loopers.domain.order.OrderOutboxStatus;
import com.loopers.support.kafka.BrokerRecords;
import com.loopers.support.kafka.OutboxTestSenders;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 단일 relay가 Outbox PENDING을 실제 broker로 보내고, broker 확인 뒤에만 PUBLISHED로 바꾸는지 확인한다 (W7 과제 3절, design.md 4절).
 * 주문은 실제 API·PG fixture·callback 경로로 확정해 Outbox 행을 만든다. relay는 테스트가 직접 만들어 고정 clock으로 한 회차씩 부른다.
 */
class OrderOutboxRelayTest extends PaymentScenarioTestBase {

    private static final OrderOutboxRelayProperties PROPERTIES =
        new OrderOutboxRelayProperties(false, Duration.ofSeconds(1), 50, Duration.ofSeconds(1), 2.0, Duration.ofMinutes(5), 5);

    @Autowired
    private OrderOutboxService orderOutboxService;

    private OrderOutboxRelay relay(OrderOutboxSender sender, Instant now) {
        return relay(sender, now, PROPERTIES);
    }

    private OrderOutboxRelay relay(OrderOutboxSender sender, Instant now, OrderOutboxRelayProperties properties) {
        return new OrderOutboxRelay(orderOutboxService, sender, properties, Clock.fixed(now, ZoneOffset.UTC));
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }

    private OrderOutboxModel onlyRow() {
        List<OrderOutboxModel> rows = outboxRows();
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    @DisplayName("PENDING 행을 저장된 key·payload 그대로 broker로 보내고, broker 확인 뒤 PUBLISHED로 바꾼다. 다음 회차는 다시 보내지 않는다.")
    @Test
    void publishesStoredRecord_thenMarksPublished() throws Exception {
        // arrange
        String topic = KafkaBrokerFixture.createTopic("order-outbox-relay", 1);
        confirmOrderThroughCallback(orderId);
        String payload = onlyRow().getPayload();
        Instant now = now();

        // act
        OrderOutboxRelay.Run run = relay(OutboxTestSenders.toBroker(topic), now).runOnce();
        OrderOutboxRelay.Run next = relay(OutboxTestSenders.toBroker(topic), now.plusSeconds(60)).runOnce();

        // assert
        assertThat(run).isEqualTo(new OrderOutboxRelay.Run(true, 1, 1, 0));
        assertThat(next).as("PUBLISHED는 다시 고르지 않는다").isEqualTo(new OrderOutboxRelay.Run(true, 0, 0, 0));
        OrderOutboxModel row = onlyRow();
        assertThat(row.getStatus()).isEqualTo(OrderOutboxStatus.PUBLISHED);
        assertThat(row.getPublishedAt()).isEqualTo(now);
        assertThat(row.getAttemptCount()).isEqualTo(1);
        List<BrokerRecords.Record> records = BrokerRecords.readAll(topic);
        assertThat(records).hasSize(1);
        assertThat(records.get(0).key()).isEqualTo(String.valueOf(orderId));
        assertThat(records.get(0).value()).as("현재 상품을 다시 읽지 않고 저장한 사실 그대로").isEqualTo(payload);
    }

    @DisplayName("broker로 보내지 못하면 같은 행을 PENDING으로 두고 다음 시도 시각·오류를 남긴다. 그 시각 전에는 고르지 않고, 뒤에는 같은 사건을 보낸다.")
    @Test
    void keepsPendingUntilRetryTime_whenBrokerUnavailable() throws Exception {
        // arrange
        String topic = KafkaBrokerFixture.createTopic("order-outbox-relay", 1);
        confirmOrderThroughCallback(orderId);
        String payload = onlyRow().getPayload();
        Instant failedAt = now();

        // act: broker에 닿지 않는 회차
        OrderOutboxRelay.Run failed = relay(OutboxTestSenders.unreachable(topic), failedAt).runOnce();

        // assert
        assertThat(failed).isEqualTo(new OrderOutboxRelay.Run(true, 1, 0, 1));
        OrderOutboxModel pending = onlyRow();
        assertThat(pending.getStatus()).isEqualTo(OrderOutboxStatus.PENDING);
        assertThat(pending.getAttemptCount()).isEqualTo(1);
        assertThat(pending.getNextAttemptAt()).isEqualTo(failedAt.plus(PROPERTIES.initialBackoff()));
        assertThat(pending.getLastError()).isNotBlank().doesNotContain(payload);

        // act & assert: 다음 시도 시각 전에는 broker가 살아 있어도 고르지 않는다
        assertThat(relay(OutboxTestSenders.toBroker(topic), failedAt).runOnce()).isEqualTo(new OrderOutboxRelay.Run(true, 0, 0, 0));
        assertThat(BrokerRecords.readAll(topic)).isEmpty();

        // act & assert: 다음 시도 시각이 되면 같은 행·같은 사건을 보낸다
        Instant retryAt = failedAt.plus(PROPERTIES.initialBackoff());
        assertThat(relay(OutboxTestSenders.toBroker(topic), retryAt).runOnce()).isEqualTo(new OrderOutboxRelay.Run(true, 1, 1, 0));
        OrderOutboxModel published = onlyRow();
        assertThat(published.getStatus()).isEqualTo(OrderOutboxStatus.PUBLISHED);
        assertThat(published.getAttemptCount()).isEqualTo(2);
        assertThat(BrokerRecords.readAll(topic)).extracting(BrokerRecords.Record::value).containsExactly(payload);
    }

    @DisplayName("시도를 기록한 뒤 보내고, send하는 동안 행은 아직 PENDING이다. PUBLISHED는 send 결과를 받은 뒤에만 저장한다.")
    @Test
    void recordsAttemptBeforeSend_andMarksPublishedOnlyAfterBrokerAck() throws Exception {
        // arrange
        String topic = KafkaBrokerFixture.createTopic("order-outbox-relay", 1);
        confirmOrderThroughCallback(orderId);
        OrderOutboxSender real = OutboxTestSenders.toBroker(topic);
        AtomicReference<OrderOutboxModel> rowAtSend = new AtomicReference<>();
        OrderOutboxSender observing = message -> {
            rowAtSend.set(onlyRow());
            return real.send(message);
        };

        // act
        relay(observing, now()).runOnce();

        // assert
        assertThat(rowAtSend.get().getStatus()).as("send 전에 PUBLISHED로 숨기지 않는다").isEqualTo(OrderOutboxStatus.PENDING);
        assertThat(rowAtSend.get().getAttemptCount()).as("시도는 send 전에 기록돼 있다").isEqualTo(1);
        assertThat(onlyRow().getStatus()).isEqualTo(OrderOutboxStatus.PUBLISHED);
    }

    @DisplayName("한 회차가 send를 기다리는 동안 다른 회차는 기다리지 않고 건너뛰어, 같은 행을 동시에 보내지 않는다.")
    @Test
    void skipsOverlappingRun() throws Exception {
        // arrange
        String topic = KafkaBrokerFixture.createTopic("order-outbox-relay", 1);
        confirmOrderThroughCallback(orderId);
        OrderOutboxSender real = OutboxTestSenders.toBroker(topic);
        CountDownLatch sending = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        OrderOutboxSender blocking = message -> {
            sending.countDown();
            await(release);
            return real.send(message);
        };
        OrderOutboxRelay relay = relay(blocking, now());

        // act
        CompletableFuture<OrderOutboxRelay.Run> first = CompletableFuture.supplyAsync(relay::runOnce);
        assertThat(sending.await(30, TimeUnit.SECONDS)).as("첫 회차가 send에 들어갔다").isTrue();
        OrderOutboxRelay.Run overlapping = relay.runOnce();
        release.countDown();

        // assert
        assertThat(overlapping).isEqualTo(new OrderOutboxRelay.Run(false, 0, 0, 0));
        assertThat(first.get(30, TimeUnit.SECONDS)).isEqualTo(new OrderOutboxRelay.Run(true, 1, 1, 0));
        assertThat(BrokerRecords.readAll(topic)).hasSize(1);
    }

    @DisplayName("한 회차는 batch-size만큼만, 먼저 기록된 행부터 보낸다.")
    @Test
    void sendsUpToBatchSize_inRelayOrder() throws Exception {
        // arrange
        String topic = KafkaBrokerFixture.createTopic("order-outbox-relay", 1);
        Long secondOrderId = saveDraft(USER_ID);
        confirmOrderThroughCallback(orderId);
        confirmOrderThroughCallback(secondOrderId);
        OrderOutboxRelayProperties oneAtATime =
            new OrderOutboxRelayProperties(false, Duration.ofSeconds(1), 1, Duration.ofSeconds(1), 2.0, Duration.ofMinutes(5), 5);
        OrderOutboxRelay relay = relay(OutboxTestSenders.toBroker(topic), now(), oneAtATime);

        // act
        OrderOutboxRelay.Run first = relay.runOnce();
        OrderOutboxRelay.Run second = relay.runOnce();

        // assert
        assertThat(first.published()).isEqualTo(1);
        assertThat(second.published()).isEqualTo(1);
        assertThat(BrokerRecords.readAll(topic)).extracting(BrokerRecords.Record::key)
            .containsExactly(String.valueOf(orderId), String.valueOf(secondOrderId));
    }

    @DisplayName("발행기가 계약과 달리 예외를 던져도 그 시도를 실패로 기록하고 행을 PENDING으로 남긴다.")
    @Test
    void recordsFailure_whenSenderThrows() throws Exception {
        // arrange
        confirmOrderThroughCallback(orderId);
        OrderOutboxSender throwing = message -> {
            throw new IllegalStateException("발행기 내부 오류(주입)");
        };

        // act
        OrderOutboxRelay.Run run = relay(throwing, now()).runOnce();

        // assert
        assertThat(run).isEqualTo(new OrderOutboxRelay.Run(true, 1, 0, 1));
        assertThat(onlyRow().getStatus()).isEqualTo(OrderOutboxStatus.PENDING);
        assertThat(onlyRow().getLastError()).contains("발행기 내부 오류");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new AssertionError("시한 안에 풀리지 않았습니다");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
