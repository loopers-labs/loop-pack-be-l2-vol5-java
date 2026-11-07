package com.loopers.application.order;

import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.order.OrderEventOutboxModel;
import com.loopers.domain.order.OutboxStatus;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.user.UserModel;
import com.loopers.infrastructure.order.KafkaOrderEventSender;
import com.loopers.support.KafkaTestRecords;
import com.loopers.support.MutableClock;
import com.loopers.support.fixture.Fixtures;
import com.loopers.utils.DatabaseCleanUp;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Outbox relay. 실제 MySQL·Kafka broker. 시각은 MutableClock 으로 넘기고, 전송 실패·대기 초과·broker 확인 뒤 중단은 sender·processor 앞에서 주입한다.
 * 이 클래스만의 topic(order-events-relay-test)을 쓴다. 주기 relay 는 test 프로필에서 꺼져 있고 테스트가 relay 를 직접 부른다.
 * "재시작"은 relay 객체를 새로 만들어 같은 DB·broker·eventId 로 회차를 다시 돌리는 것이다 (OS 강제 종료가 아니다).
 * 실행: ./gradlew :apps:commerce-api:test --tests '*OrderEventRelayTest' (Docker 필요, Testcontainers).
 */
@SpringBootTest(properties = "kafka.topics.order-events.name=" + OrderEventRelayTest.TOPIC)
@Import(OrderEventRelayTest.TestConfig.class)
class OrderEventRelayTest {
    static final String TOPIC = "order-events-relay-test";
    private static final Instant START = Instant.parse("2026-09-07T01:00:00.123456Z");

    @TestConfiguration
    static class TestConfig {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(START, ZoneId.of("Asia/Seoul"));
        }

        @Bean
        @Primary
        ControllableSender controllableSender(KafkaOrderEventSender delegate) {
            return new ControllableSender(delegate);
        }
    }

    /** 실제 Kafka 전송 앞. PASS 는 그대로, FAIL 은 실패한 future, HOLD 는 테스트가 끝낼 때까지 끝나지 않는 future. */
    static class ControllableSender implements OrderEventSender {
        enum Mode { PASS, FAIL, HOLD }

        private final OrderEventSender delegate;
        private final List<String> keys = new CopyOnWriteArrayList<>();
        private final List<CompletableFuture<Sent>> held = new CopyOnWriteArrayList<>();
        private volatile Mode mode = Mode.PASS;
        private volatile CountDownLatch holding = new CountDownLatch(1);

        ControllableSender(OrderEventSender delegate) {
            this.delegate = delegate;
        }

        @Override
        public CompletableFuture<Sent> send(String messageKey, String payload) {
            keys.add(messageKey);
            return switch (mode) {
                case PASS -> delegate.send(messageKey, payload);
                case FAIL -> CompletableFuture.failedFuture(new RecordTooLargeException("주입된 전송 실패"));
                case HOLD -> {
                    CompletableFuture<Sent> future = new CompletableFuture<>();
                    held.add(future);
                    holding.countDown();
                    yield future;
                }
            };
        }

        void reset() {
            mode = Mode.PASS;
            keys.clear();
            held.forEach(future -> future.cancel(false));
            held.clear();
            holding = new CountDownLatch(1);
        }
    }

    /** broker 확인까지는 진짜로 하고, 발행 확인 저장(PUBLISHED)만 하지 않고 멈춘다. 확인 받은 위치는 남긴다. 나머지는 실제 processor 로. */
    static class StopBeforePublishedSave extends OrderEventOutboxProcessor {
        private final OrderEventOutboxProcessor delegate;
        private final List<OrderEventSender.Sent> acked = new CopyOnWriteArrayList<>();

        StopBeforePublishedSave(OrderEventOutboxProcessor delegate) {
            super(null, null);
            this.delegate = delegate;
        }

        @Override
        public List<Claimed> claimDue(ZonedDateTime now) {
            return delegate.claimDue(now);
        }

        @Override
        public Optional<Claimed> claim(String eventId, ZonedDateTime now) {
            return delegate.claim(eventId, now);
        }

        @Override
        public void markPublished(String eventId, ZonedDateTime now, OrderEventSender.Sent sent) {
            acked.add(sent);
            throw new IllegalStateException("주입된 중단: broker 확인 뒤, PUBLISHED 저장 전");
        }

        @Override
        public OutboxStatus recordFailure(String eventId, ZonedDateTime now, String error) {
            return delegate.recordFailure(eventId, now, error);
        }

        @Override
        public boolean resume(String eventId, ZonedDateTime now) {
            return delegate.resume(eventId, now);
        }
    }

    @Autowired
    private OrderFacade orderFacade;
    @Autowired
    private OrderEventRelay relay;
    @Autowired
    private OrderEventOutboxProcessor processor;
    @Autowired
    private OutboxRelayProperties properties;
    @Autowired
    private ControllableSender sender;
    @Autowired
    private MutableClock clock;
    @Autowired
    private Fixtures fixtures;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private Map<TopicPartition, Long> endAtStart;
    private UserModel user;
    private ProductModel productA;
    private ProductModel productB;

    @BeforeEach
    void setUp() {
        clock.set(START);
        endAtStart = KafkaTestRecords.endOffsets(TOPIC);
        fixtures.catalogRevision();
        user = fixtures.user();
        BrandModel brand = fixtures.brand("브랜드");
        productA = fixtures.product(brand.getId(), "상품 A", 1_000L, 50);
        productB = fixtures.product(brand.getId(), "상품 B", 500L, 50);
    }

    @AfterEach
    void tearDown() {
        sender.reset();
        databaseCleanUp.truncateAllTables();
    }

    /** 기존 주문 API 로 확정한 주문 (PG 는 FakePaymentGateway 기본 SUCCESS). Outbox PENDING 한 행이 commit 된 상태. */
    private Long confirmedOrder() {
        OrderInfo order = orderFacade.createOrder(user.getId(), List.of(new OrderItemCommand(productA.getId(), 2), new OrderItemCommand(productB.getId(), 1)));
        orderFacade.confirmOrder(user.getId(), order.id(), "attempt-" + order.id(), "SAMSUNG", "1234-5678-9814-1451");
        return order.id();
    }

    private OrderEventOutboxModel outboxOf(Long orderId) {
        List<OrderEventOutboxModel> rows = fixtures.outboxOf(orderId);
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    /** 이 테스트가 시작한 뒤 기록된 그 eventId 의 record. 테스트마다 DB 를 비워 orderId(→ eventId)가 다시 1 부터라 앞 테스트의 record 는 뺀다. */
    private List<ConsumerRecord<String, String>> recordsOf(String eventId) {
        return KafkaTestRecords.readAll(TOPIC).stream()
            .filter(record -> record.offset() >= endAtStart.getOrDefault(new TopicPartition(record.topic(), record.partition()), 0L))
            .filter(record -> record.value().contains("\"eventId\":\"" + eventId + "\""))
            .toList();
    }

    private OrderEventRelay newRelay(OrderEventOutboxProcessor processor) {
        return new OrderEventRelay(processor, sender, properties, clock);
    }

    @DisplayName("relay 는 저장된 key·payload 문자열을 그대로 보내고, broker 확인 뒤 PUBLISHED·publishedAt·기록 위치를 저장한다.")
    @Test
    void publishesStoredPayload_andMarksPublishedAfterBrokerAck() {
        Long orderId = confirmedOrder();
        OrderEventOutboxModel pending = outboxOf(orderId);
        clock.advance(Duration.ofMillis(10));

        OrderEventRelay.Run run = relay.runDue();

        assertThat(run).isEqualTo(new OrderEventRelay.Run(false, 1, 1, 0, 0, false));
        OrderEventOutboxModel published = outboxOf(orderId);
        assertThat(published.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(published.getPublishedAt().toInstant()).isEqualTo(START.plusMillis(10));
        assertThat(published.getCreatedAt()).isEqualTo(pending.getCreatedAt());
        assertThat(published.getAttemptCount()).isEqualTo(1);
        assertThat(published.getFailureCount()).isZero();

        ConsumerRecord<String, String> record = KafkaTestRecords.readAt(TOPIC, published.getPublishedPartition(), published.getPublishedOffset());
        assertThat(record.key()).isEqualTo(pending.getMessageKey()).isEqualTo(String.valueOf(orderId));
        assertThat(record.value()).isEqualTo(pending.getPayload());

        assertThat(relay.runDue().claimed()).isZero();
        assertThat(recordsOf(published.getEventId())).hasSize(1);
    }

    @DisplayName("[commit 후 relay 실행 전 중단] 확정 commit 뒤 relay 가 돌지 않으면 Outbox 는 PENDING, broker 에는 record 가 없다. 새로 만든 relay 가 저장 행에서 같은 사건을 보낸다.")
    @Test
    void newRelayPublishesPendingRow_whenStoppedBeforeRelay() {
        Map<TopicPartition, Long> endBefore = KafkaTestRecords.endOffsets(TOPIC);
        Long orderId = confirmedOrder();

        OrderEventOutboxModel pending = outboxOf(orderId);
        assertThat(pending.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(pending.getAttemptCount()).isZero();
        assertThat(KafkaTestRecords.endOffsets(TOPIC)).isEqualTo(endBefore);

        OrderEventRelay restarted = newRelay(processor);
        OrderEventRelay.Run run = restarted.runDue();

        assertThat(run.published()).isEqualTo(1);
        assertThat(outboxOf(orderId).getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(recordsOf(pending.getEventId())).extracting(ConsumerRecord::value).containsExactly(pending.getPayload());
    }

    @DisplayName("[broker 확인 뒤 PUBLISHED 저장 전 중단] 같은 행은 PENDING 으로 남고, 재시작한 relay 가 시도 lease 뒤 같은 사건을 다시 보낸다. broker 에는 같은 eventId 의 record 가 별도 offset 으로 2개다.")
    @Test
    void republishesSameEvent_whenStoppedAfterBrokerAckBeforePublishedSave() {
        Long orderId = confirmedOrder();
        String eventId = outboxOf(orderId).getEventId();
        StopBeforePublishedSave stopping = new StopBeforePublishedSave(processor);

        OrderEventRelay.Run first = newRelay(stopping).runDue();

        assertThat(first.aborted()).isTrue();
        assertThat(stopping.acked).hasSize(1);
        OrderEventSender.Sent firstAck = stopping.acked.get(0);
        OrderEventOutboxModel afterStop = outboxOf(orderId);
        assertThat(afterStop.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(afterStop.getAttemptCount()).isEqualTo(1);
        assertThat(afterStop.getPublishedAt()).isNull();
        assertThat(KafkaTestRecords.readAt(TOPIC, firstAck.partition(), firstAck.offset()).value()).isEqualTo(afterStop.getPayload());

        OrderEventRelay restarted = newRelay(processor);
        assertThat(restarted.runDue().claimed()).isZero();
        clock.advance(properties.sendTimeout());
        OrderEventRelay.Run second = restarted.runDue();

        assertThat(second.published()).isEqualTo(1);
        OrderEventOutboxModel published = outboxOf(orderId);
        assertThat(published.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(published.getAttemptCount()).isEqualTo(2);
        assertThat(published.getPublishedOffset()).isNotEqualTo(firstAck.offset());
        assertThat(recordsOf(eventId))
            .extracting(ConsumerRecord::partition, ConsumerRecord::offset)
            .containsExactly(
                tuple(firstAck.partition(), firstAck.offset()),
                tuple(published.getPublishedPartition(), published.getPublishedOffset()));
        assertThat(recordsOf(eventId)).extracting(ConsumerRecord::value).containsOnly(afterStop.getPayload());
    }

    @DisplayName("전송이 실패하면 같은 행이 PENDING 으로 남고 재시도 시각(1s, 2s)이 늦춰진다. 연속 3회 실패하면 BLOCKED 가 되어 relay 가 더는 고르지 않고, eventId 재개로 같은 행이 다시 발행된다.")
    @Test
    void blocksAfterThreeFailures_andResumesSameRow() {
        Long orderId = confirmedOrder();
        String eventId = outboxOf(orderId).getEventId();
        sender.mode = ControllableSender.Mode.FAIL;

        assertThat(relay.runDue().failed()).isEqualTo(1);
        OrderEventOutboxModel once = outboxOf(orderId);
        assertThat(once.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(once.getFailureCount()).isEqualTo(1);
        assertThat(once.getLastError()).startsWith("RecordTooLargeException: 주입된 전송 실패");
        assertThat(once.getNextAttemptAt().toInstant()).isEqualTo(START.plusSeconds(1));

        assertThat(relay.runDue().claimed()).isZero();
        clock.advance(Duration.ofSeconds(1));
        assertThat(relay.runDue().failed()).isEqualTo(1);
        assertThat(outboxOf(orderId).getNextAttemptAt().toInstant()).isEqualTo(START.plusSeconds(3));

        clock.advance(Duration.ofSeconds(2));
        assertThat(relay.runDue().blocked()).isEqualTo(1);
        OrderEventOutboxModel blocked = outboxOf(orderId);
        assertThat(blocked.getStatus()).isEqualTo(OutboxStatus.BLOCKED);
        assertThat(blocked.getFailureCount()).isEqualTo(3);
        assertThat(blocked.getAttemptCount()).isEqualTo(3);

        clock.advance(Duration.ofHours(1));
        assertThat(relay.runDue().claimed()).isZero();
        assertThat(sender.keys).hasSize(3);

        sender.mode = ControllableSender.Mode.PASS;
        OrderEventRelay.Run resumed = relay.resumeAndRun(eventId);

        assertThat(resumed.published()).isEqualTo(1);
        OrderEventOutboxModel published = outboxOf(orderId);
        assertThat(published.getId()).isEqualTo(blocked.getId());
        assertThat(published.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(published.getAttemptCount()).isEqualTo(4);
        assertThat(published.getFailureCount()).isZero();
        assertThat(published.getLastError()).startsWith("RecordTooLargeException");
        assertThat(recordsOf(eventId)).hasSize(1);
        assertThat(relay.resumeAndRun(eventId).claimed()).isZero();
    }

    @DisplayName("broker 확인 대기를 넘기면 실패로 기록하고 PENDING 을 유지한다. 대기를 넘긴 send 가 나중에 성공해도 상태는 바뀌지 않는다.")
    @Test
    void lateSendSuccessDoesNotChangeState_afterTimeout() throws Exception {
        Long orderId = confirmedOrder();
        sender.mode = ControllableSender.Mode.HOLD;

        OrderEventRelay.Run run = relay.runDue();

        assertThat(run.failed()).isEqualTo(1);
        OrderEventOutboxModel timedOut = outboxOf(orderId);
        assertThat(timedOut.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(timedOut.getLastError()).startsWith("TimeoutException: broker 확인 대기");

        sender.held.get(0).complete(new OrderEventSender.Sent(TOPIC, 0, 999L));
        TimeUnit.MILLISECONDS.sleep(200);

        OrderEventOutboxModel after = outboxOf(orderId);
        assertThat(after.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(after.getPublishedAt()).isNull();
        assertThat(after.getPublishedOffset()).isNull();
        assertThat(after.getFailureCount()).isEqualTo(1);
    }

    @DisplayName("한 회차는 재시도 시각·id 순으로 batchSize 건까지만 고른다. 나머지는 다음 회차가 보낸다.")
    @Test
    void picksDueRowsInOrder_uptoBatchSize() {
        int total = properties.batchSize() + 1;
        List<Long> orderIds = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            orderIds.add(confirmedOrder());
            clock.advance(Duration.ofMillis(1));
        }

        OrderEventRelay.Run first = relay.runDue();

        assertThat(first.published()).isEqualTo(properties.batchSize());
        assertThat(sender.keys).containsExactlyElementsOf(orderIds.subList(0, properties.batchSize()).stream().map(String::valueOf).toList());
        assertThat(outboxOf(orderIds.get(total - 1)).getStatus()).isEqualTo(OutboxStatus.PENDING);

        assertThat(relay.runDue().published()).isEqualTo(1);
        assertThat(orderIds).allSatisfy(id -> assertThat(outboxOf(id).getStatus()).isEqualTo(OutboxStatus.PUBLISHED));
    }

    @DisplayName("회차가 도는 동안 다른 회차는 기다리지 않고 건너뛴다 (한 relay 안에서 회차가 겹치지 않는다).")
    @Test
    void skipsOverlappingRun() throws Exception {
        confirmedOrder();
        sender.mode = ControllableSender.Mode.HOLD;
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<OrderEventRelay.Run> running = executor.submit(relay::runDue);
            assertThat(sender.holding.await(5, TimeUnit.SECONDS)).isTrue();

            assertThat(relay.runDue()).isEqualTo(OrderEventRelay.Run.SKIPPED);

            assertThat(running.get(10, TimeUnit.SECONDS).failed()).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }
}
