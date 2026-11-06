package com.loopers.crossapp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.order.OrderCommand;
import com.loopers.application.order.OrderConfirmedOutboxRelay;
import com.loopers.application.order.OrderFacade;
import com.loopers.application.order.OutboxMessageSender;
import com.loopers.application.order.OutboxRelayProperties;
import com.loopers.application.order.OutboxRelayResult;
import com.loopers.application.order.OutboxSendResult;
import com.loopers.application.payment.PaymentFacade;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderConfirmedEvent;
import com.loopers.domain.order.OrderConfirmedOutbox;
import com.loopers.domain.order.OrderConfirmedOutboxRepository;
import com.loopers.domain.payment.PaymentAttempt;
import com.loopers.domain.point.Point;
import com.loopers.domain.product.Price;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.StockReservation;
import com.loopers.domain.purchase.ProcessedOrderConfirmation;
import com.loopers.domain.purchase.ProductPurchaseActivity;
import com.loopers.domain.purchase.PurchaseActivityRepository;
import com.loopers.domain.user.User;
import com.loopers.infrastructure.order.OrderJpaRepository;
import com.loopers.infrastructure.payment.FakePaymentGateway;
import com.loopers.infrastructure.payment.PaymentAttemptJpaRepository;
import com.loopers.infrastructure.point.PointJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.infrastructure.product.StockReservationJpaRepository;
import com.loopers.infrastructure.user.UserJpaRepository;
import com.loopers.testcontainers.KafkaTestContainersConfig;
import com.loopers.utils.DatabaseCleanUp;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mockingDetails;

/**
 * 7주차 과제 6절(두 중단 구간)·7절(E2E) (📄 p.5~7, 설계 D37~D39). 기존 주문 API + 가짜 PG + 실제 MySQL·Kafka + 실제 streamer consumer 를 한 실행에 연결한다.
 * 가짜 handler 로 업무 완료를 대신하지 않는다.
 *
 * <ul>
 *   <li>commit 후 relay 실행 전 중단: test 는 relay 자동 실행이 꺼져 있어 확정 commit 뒤 Outbox PENDING 만 남는다 → 새 relay 객체로 발행</li>
 *   <li>broker 확인 뒤 PUBLISHED 저장 전 중단: 전송기를 감싼 래퍼가 실제 send 성공 metadata 를 받은 뒤 예외를 던져 결과 TX 에 못 가게 한다.
 *       <b>통제된 중단이며 OS 강제 종료·프로세스 재기동이 아니다</b> (📄 p.5)</li>
 *   <li>재시작: 실제 전송기를 넣은 새 relay 객체. 같은 DB·Kafka·eventId. 테스트는 Outbox 를 직접 PENDING 으로 덮어쓰지 않고 상태를 확인만 한다 (📄 p.6)</li>
 * </ul>
 * 기록은 build/w7-recovery/e2e.json 에 남긴다.
 */
@SpringBootTest(
    classes = CrossAppTestConfig.class,
    properties = {
        "order-events.topic=" + OutboxDeliveryE2ETest.TOPIC,
        "purchase-activity.topic=" + OutboxDeliveryE2ETest.TOPIC,
        "purchase-activity.group-id=" + OutboxDeliveryE2ETest.GROUP,
        "purchase-activity.fetch-min-bytes=1",
        "purchase-activity.fetch-max-wait-ms=100",
        "purchase-activity.retry.max-retries=2",
        "purchase-activity.retry.initial-backoff-ms=100",
        "purchase-activity.retry.multiplier=2",
        "purchase-activity.dead-letter.send-timeout-ms=1000"
    }
)
class OutboxDeliveryE2ETest {

    static final String TOPIC = "order-events-OutboxDeliveryE2ETest";
    static final String GROUP = "purchase-activity-OutboxDeliveryE2ETest";
    private static final TopicPartition PARTITION = new TopicPartition(TOPIC, 0);
    private static final Path RECORD = Path.of("build", "w7-recovery", "e2e.json");
    private static final String CARD_TYPE = "TEST";
    private static final String CARD_NO = "0000-0000-0000-0000";
    private static final Instant NOW = Instant.parse("2026-11-07T04:00:00.123456789Z");
    private static final Instant CONFIRMED_AT = Instant.parse("2026-11-07T04:00:00.123Z");
    private static final Duration CONSUME_DEADLINE = Duration.ofSeconds(30);

    static {
        KafkaTestContainersConfig.createTopic(TOPIC, 1);
    }

    /**
     * broker 확인 뒤 PUBLISHED 저장 전 중단용 래퍼 (D37). 실제 전송기에 위임해 metadata 를 받아 기록한 뒤 예외를 던진다.
     * 통제된 중단이며 OS 강제 종료가 아니다.
     */
    static class InterruptAfterBrokerAckSender implements OutboxMessageSender {
        private final OutboxMessageSender delegate;
        private final AtomicReference<OutboxSendResult> captured = new AtomicReference<>();

        InterruptAfterBrokerAckSender(OutboxMessageSender delegate) {
            this.delegate = delegate;
        }

        OutboxSendResult captured() {
            return captured.get();
        }

        @Override
        public OutboxSendResult send(String messageKey, String payload) {
            OutboxSendResult result = delegate.send(messageKey, payload);
            captured.set(result);
            throw new IllegalStateException("통제된 중단: broker 확인(offset " + result.offset() + ") 뒤 PUBLISHED 저장 전. OS 강제 종료가 아니다");
        }
    }

    @MockitoBean
    private Clock clock;
    /** streamer 장부 저장소 spy. 장부 조회 호출 수로 consumer 수신 횟수를, 저장 호출 수로 완료 기록 수를 센다 (D39). */
    @MockitoSpyBean
    private PurchaseActivityRepository purchaseActivityRepository;

    @Autowired
    private OrderFacade orderFacade;
    @Autowired
    private PaymentFacade paymentFacade;
    @Autowired
    private FakePaymentGateway fakePaymentGateway;
    @Autowired
    private OutboxMessageSender realSender;
    @Autowired
    private OutboxRelayProperties relayProperties;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private OrderConfirmedOutboxRepository outboxRepository;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private UserJpaRepository userJpaRepository;
    @Autowired
    private ProductJpaRepository productJpaRepository;
    @Autowired
    private PointJpaRepository pointJpaRepository;
    @Autowired
    private OrderJpaRepository orderJpaRepository;
    @Autowired
    private PaymentAttemptJpaRepository paymentAttemptJpaRepository;
    @Autowired
    private StockReservationJpaRepository stockReservationJpaRepository;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @BeforeEach
    void setUp() {
        fakePaymentGateway.reset();
        given(clock.instant()).willReturn(NOW);
        given(clock.getZone()).willReturn(ZoneId.of("Asia/Seoul"));
    }

    @DisplayName("주문 API → PG SUCCESS → 같은 TX Outbox → [commit 후 relay 전 중단] → relay 첫 broker 확인 뒤 PUBLISHED 저장 전 중단 → 새 relay 재발행 → consumer 2회 수신에도 완료 기록 1·활동 2행(2·1)·재고/포인트 불변")
    @Test
    void outboxDeliveryEndToEnd_withInterruptAfterBrokerAck() throws IOException {
        // ---- 1. 기존 API 로 합성 주문(상품 P1 × 2, P2 × 1) + PG SUCCESS 를 내부 확정 경로에 적용 (📄 p.6 7절 1) ----
        Long userId = userJpaRepository.save(new User()).getId();
        Point point = new Point(userId);
        point.charge(10_000L);
        pointJpaRepository.save(point);
        Long product1 = saveProduct("상품 P1", 1000L, 10);
        Long product2 = saveProduct("상품 P2", 2000L, 10);
        Long orderId = orderFacade.createOrder(userId, List.of(new OrderCommand.Item(product1, 2), new OrderCommand.Item(product2, 1))).orderId();
        orderFacade.confirmOrder(userId, orderId, new OrderCommand.Confirm("e2e-1", CARD_TYPE, CARD_NO));
        PaymentAttempt attempt = attemptOf(orderId);
        fakePaymentGateway.markSuccess(attempt.getTransactionKey());

        paymentFacade.applyCallback(attempt.getProviderOrderId(), attempt.getTransactionKey());

        // ---- 2. 확정 주문·예약 소비·같은 eventId 의 Outbox 1행 (📄 p.6 7절 2). 이 시점이 "commit 후 relay 실행 전 중단" 상태다 (6절) ----
        String eventId = OrderConfirmedEvent.eventIdOf(orderId);
        Order order = orderJpaRepository.findById(orderId).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(Order.OrderStatus.CONFIRMED);
        assertThat(order.getConfirmedAt().toInstant()).isEqualTo(CONFIRMED_AT);
        assertThat(reservationStatusesOf(orderId)).hasSize(2).containsOnly(StockReservation.Status.CONSUMED);
        OrderConfirmedOutbox outbox = outboxRepository.findByOrderId(orderId).orElseThrow();
        assertThat(outbox.getEventId()).isEqualTo(eventId);
        assertThat(outbox.getStatus()).isEqualTo(OrderConfirmedOutbox.Status.PENDING);
        assertThat(outbox.getAttemptCount()).isZero();
        assertThat(readAll()).isEmpty(); // relay 가 돌지 않았으므로 broker 에 아무것도 없다
        // 기준값: 확정 직후의 재고·예약·포인트·PG
        int stock1Before = stockOf(product1);
        int stock2Before = stockOf(product2);
        long pointBefore = pointBalanceOf(userId);
        int pgCreateBefore = fakePaymentGateway.createCount();
        int pgTxBefore = fakePaymentGateway.transactions().size();
        assertThat(pgCreateBefore).isEqualTo(1);
        assertThat(pgTxBefore).isEqualTo(1);

        // ---- 3. relay 의 첫 broker 확인 뒤 PUBLISHED 저장 전에 중단 (📄 p.6 7절 3, p.5 6절) ----
        InterruptAfterBrokerAckSender interrupting = new InterruptAfterBrokerAckSender(realSender);
        OrderConfirmedOutboxRelay interruptedRelay = newRelay(interrupting);
        IllegalStateException interruption = assertThrows(IllegalStateException.class, interruptedRelay::runOnce);
        assertThat(interruption.getMessage()).contains("통제된 중단");
        OutboxSendResult firstAck = interrupting.captured();
        assertThat(firstAck).isNotNull();
        assertThat(firstAck.isPublished()).as("실제 send 성공 metadata 를 확보했다").isTrue();
        // 중단 직후 상태를 확인만 한다. 테스트가 PENDING 으로 덮어쓰지 않는다 (📄 p.6)
        OrderConfirmedOutbox afterInterrupt = outboxRepository.findByOrderId(orderId).orElseThrow();
        assertThat(afterInterrupt.getStatus()).isEqualTo(OrderConfirmedOutbox.Status.PENDING);
        assertThat(afterInterrupt.getAttemptCount()).isEqualTo(1);
        assertThat(afterInterrupt.getPublishedAt()).isNull();
        List<ConsumerRecord<String, byte[]>> afterFirst = readAll();
        assertThat(afterFirst).hasSize(1);
        assertThat(afterFirst.get(0).offset()).isEqualTo(firstAck.offset());

        // ---- 4. 같은 Outbox 에서 새 relay 객체로 재시작·재발행 (📄 p.6 7절 4, 설계 D38) ----
        OrderConfirmedOutboxRelay restartedRelay = newRelay(realSender);
        OutboxRelayResult restart = restartedRelay.runOnce();
        assertThat(restart.reason()).isEqualTo(OutboxRelayResult.Reason.COMPLETED);
        assertThat(restart.picked()).isEqualTo(1);
        assertThat(restart.published()).isEqualTo(1);
        OrderConfirmedOutbox afterRestart = outboxRepository.findByOrderId(orderId).orElseThrow();
        assertThat(afterRestart.getStatus()).isEqualTo(OrderConfirmedOutbox.Status.PUBLISHED);
        assertThat(afterRestart.getAttemptCount()).isEqualTo(2);
        assertThat(afterRestart.getPublishedAt().toInstant()).isEqualTo(CONFIRMED_AT); // 고정 clock 의 밀리초 절단값

        // broker: 같은 eventId 의 별도 record 2개, 다른 offset, 같은 bytes (📄 p.6 "같은 eventId가 별도 offset으로 기록될 수 있습니다")
        List<ConsumerRecord<String, byte[]>> records = readAll();
        assertThat(records).hasSize(2);
        assertThat(records.get(0).offset()).isEqualTo(firstAck.offset());
        assertThat(records.get(1).offset()).isGreaterThan(firstAck.offset());
        for (ConsumerRecord<String, byte[]> record : records) {
            assertThat(record.key()).isEqualTo(String.valueOf(orderId));
            assertThat(new String(record.value(), StandardCharsets.UTF_8)).isEqualTo(afterRestart.getPayload());
            assertThat(objectMapper.readTree(record.value()).get("eventId").asText()).isEqualTo(eventId);
        }

        // ---- 5. consumer 가 같은 사건을 2회 받아도 완료 기록 1·활동 2행 (📄 p.6 7절 5) ----
        awaitConsumed(records.get(1).offset() + 1); // (b) committed offset 이 재발행 record 다음까지 전진
        long lookups = ledgerLookupCalls();
        long saves = ledgerSaveCalls();
        assertThat(lookups).as("(a) consumer 장부 조회 = 수신 횟수").isGreaterThanOrEqualTo(2);
        assertThat(saves).as("완료 기록 저장은 1회").isEqualTo(1);
        ProcessedOrderConfirmation ledger = purchaseActivityRepository.findProcessedByOrderId(orderId).orElseThrow();
        assertThat(ledger.getEventId()).isEqualTo(eventId);
        assertThat(ledger.getConfirmedAt().toInstant()).isEqualTo(CONFIRMED_AT);
        List<ProductPurchaseActivity> activities = purchaseActivityRepository.findActivitiesByOrderId(orderId);
        assertThat(activities).hasSize(2);
        assertThat(activities.get(0).getProductId()).isEqualTo(product1);
        assertThat(activities.get(0).getQuantity()).isEqualTo(2);
        assertThat(activities.get(1).getProductId()).isEqualTo(product2);
        assertThat(activities.get(1).getQuantity()).isEqualTo(1);

        // 재고·예약·포인트·PG 는 확정 직후 기준값 그대로 (📄 p.6 "재고·포인트 예약 추가 소비·반환 없음, PG 주문 포인트 추가 차감 없음")
        assertThat(stockOf(product1)).isEqualTo(stock1Before);
        assertThat(stockOf(product2)).isEqualTo(stock2Before);
        assertThat(reservationStatusesOf(orderId)).hasSize(2).containsOnly(StockReservation.Status.CONSUMED);
        assertThat(pointBalanceOf(userId)).isEqualTo(pointBefore).isEqualTo(10_000L);
        assertThat(fakePaymentGateway.createCount()).isEqualTo(pgCreateBefore);
        assertThat(fakePaymentGateway.transactions()).hasSize(pgTxBefore);
        assertThat(outboxRepository.findByEventId(eventId)).isPresent();

        // ---- 기록 (D41) ----
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("phase", "e2e (6절 중단 + 7절)");
        record.put("fixedClock", NOW.toString());
        record.put("topic", TOPIC);
        record.put("orderId", orderId);
        record.put("eventId", eventId);
        record.put("items", List.of(Map.of("productId", product1, "quantity", 2), Map.of("productId", product2, "quantity", 1)));
        record.put("pgTransactionKey", attemptOf(orderId).getTransactionKey());
        record.put("interrupt", Map.of(
            "kind", "통제된 중단 (전송기 래퍼 예외). OS 강제 종료가 아니다",
            "firstAckOffset", firstAck.offset(), "outboxAfterInterrupt", afterInterrupt.getStatus().name(), "attemptCountAfterInterrupt", afterInterrupt.getAttemptCount()));
        record.put("restart", Map.of(
            "republishOffset", records.get(1).offset(), "outboxFinal", afterRestart.getStatus().name(), "attemptCountFinal", afterRestart.getAttemptCount()));
        record.put("brokerRecordOffsets", records.stream().map(ConsumerRecord::offset).toList());
        record.put("consumer", Map.of("committedOffset", committedOffset(), "ledgerLookupCalls", lookups, "ledgerSaveCalls", saves,
            "ledgerRows", 1, "purchaseActivityRows", activities.size()));
        record.put("invariants", Map.of(
            "stock", Map.of(String.valueOf(product1), stockOf(product1), String.valueOf(product2), stockOf(product2)),
            "pointBalance", pointBalanceOf(userId), "pgCreateCount", fakePaymentGateway.createCount(),
            "pgTransactionCount", fakePaymentGateway.transactions().size()));
        Files.createDirectories(RECORD.getParent());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(RECORD.toFile(), record);

        // ---- 정리: 모든 확인 뒤에만 자신이 만든 행을 정리한다 (📄 p.7) ----
        databaseCleanUp.truncateAllTables();
    }

    /** 재시작·중단용 relay 객체를 새로 만든다 (D38). 같은 DB·같은 설정, 전송기만 바꿔 넣는다. */
    private OrderConfirmedOutboxRelay newRelay(OutboxMessageSender sender) {
        return new OrderConfirmedOutboxRelay(outboxRepository, sender, relayProperties, clock, transactionManager);
    }

    private void awaitConsumed(long expectedCommittedOffset) {
        await().atMost(CONSUME_DEADLINE).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
            long committed = committedOffset();
            assertThat(committed).as("group %s committed offset (기대 %d)", GROUP, expectedCommittedOffset).isEqualTo(expectedCommittedOffset);
        });
    }

    private long committedOffset() {
        try (AdminClient admin = AdminClient.create(
            Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaTestContainersConfig.bootstrapServers()))) {
            OffsetAndMetadata committed = admin.listConsumerGroupOffsets(GROUP)
                .partitionsToOffsetAndMetadata().get(10, TimeUnit.SECONDS).get(PARTITION);
            return committed == null ? -1 : committed.offset();
        } catch (Exception e) {
            throw new IllegalStateException("committed offset 조회 실패", e);
        }
    }

    private long ledgerLookupCalls() {
        return mockingDetails(purchaseActivityRepository).getInvocations().stream()
            .filter(invocation -> invocation.getMethod().getName().equals("findProcessedByEventId"))
            .count();
    }

    private long ledgerSaveCalls() {
        return mockingDetails(purchaseActivityRepository).getInvocations().stream()
            .filter(invocation -> invocation.getMethod().getName().equals("saveProcessed"))
            .count();
    }

    /** 이 클래스 전용 topic 의 partition 0 을 처음부터 끝까지 읽는다 (listener 와 다른 group, offset commit 없음). */
    private List<ConsumerRecord<String, byte[]>> readAll() {
        Map<String, Object> props = Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaTestContainersConfig.bootstrapServers(),
            ConsumerConfig.GROUP_ID_CONFIG, "e2e-reader-" + UUID.randomUUID(),
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class,
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        List<ConsumerRecord<String, byte[]>> records = new ArrayList<>();
        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(props)) {
            consumer.assign(List.of(PARTITION));
            consumer.seekToBeginning(List.of(PARTITION));
            long end = consumer.endOffsets(List.of(PARTITION)).get(PARTITION);
            long deadline = System.currentTimeMillis() + 10_000;
            while (consumer.position(PARTITION) < end && System.currentTimeMillis() < deadline) {
                consumer.poll(Duration.ofMillis(500)).forEach(records::add);
            }
        }
        return records;
    }

    private Long saveProduct(String name, long price, int stock) {
        Product product = new Product(1L, name, new Price(price));
        product.changeStock(stock);
        return productJpaRepository.save(product).getId();
    }

    private PaymentAttempt attemptOf(Long orderId) {
        return paymentAttemptJpaRepository.findByOrderId(orderId).orElseThrow();
    }

    private List<StockReservation.Status> reservationStatusesOf(Long orderId) {
        return stockReservationJpaRepository.findAllByOrderId(orderId).stream().map(StockReservation::getStatus).toList();
    }

    private int stockOf(Long productId) {
        return productJpaRepository.findById(productId).orElseThrow().getStock().getQuantity();
    }

    private long pointBalanceOf(Long userId) {
        return pointJpaRepository.findByUserId(userId).orElseThrow().getBalance();
    }
}
