package com.loopers.crossapp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.order.OrderCommand;
import com.loopers.application.order.OrderFacade;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderConfirmedEvent;
import com.loopers.domain.payment.PaymentAttempt;
import com.loopers.domain.payment.PaymentTransaction;
import com.loopers.domain.product.Price;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.StockReservation;
import com.loopers.domain.purchase.ProcessedOrderConfirmation;
import com.loopers.domain.purchase.ProductPurchaseActivity;
import com.loopers.domain.purchase.PurchaseActivityRepository;
import com.loopers.domain.user.User;
import com.loopers.infrastructure.order.OrderJpaRepository;
import com.loopers.infrastructure.payment.FakeOrderConfirmedPublisher;
import com.loopers.infrastructure.payment.FakePaymentGateway;
import com.loopers.infrastructure.payment.KafkaOrderConfirmedPublisher;
import com.loopers.infrastructure.payment.PaymentAttemptJpaRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.BDDMockito.given;

/**
 * 7주차 과제 5절 "새 격리 실행에서 W6 누락 상태를 재현하고 복구하기" (📄 p.4~5).
 * 이번 커밋은 준비 5-1 ~ 5-3 까지다. 복구 5-4 ~ 5-6 은 W7 Outbox·relay 가 생긴 뒤 같은 테스트 메서드 안에 이어 붙인다.
 *
 * <p>준비 (W6 직접 발행 경로 {@link W6DirectPublishPath} 로 두 합성 주문을 확정):
 * <ul>
 *   <li>주문 A: 확정 TX commit 뒤, 가짜 발행기의 send 직전 hook 이 예외를 던져 send 를 막는다. 주문·예약은 확정된 채 남고 broker record 는 없다.
 *       이 중단은 통제된 hook 이며 프로세스 종료가 아니다 (📄 p.5 "예외를 던진 테스트를 OS 강제 종료 시험으로 표현하지 않습니다").</li>
 *   <li>주문 B: hook 을 통과해 실제 broker 에 발행되고, 같은 context 에 올린 commerce-streamer 의 실제 listener 가 소비를 끝낸다.</li>
 * </ul>
 * 준비와 복구 사이에 DB·broker·PG 를 초기화하거나 @AfterEach 정리를 끼우지 않는다 (📄 p.4 5-3). 정리는 테스트 맨 끝에서만 한다.
 * 이번 실행의 사실은 build/w7-recovery/observation.json 에 남긴다. W6 기록(build/delivery-gap/, 설계 문서 8절)과 섞지 않는다.
 */
@SpringBootTest(
    classes = CrossAppTestConfig.class,
    properties = {
        // 발행 쪽(commerce-api)과 소비 쪽(streamer)이 같은 격리 topic 을 본다
        "order-events.topic=" + W6GapReproductionAndRecoveryTest.TOPIC,
        "purchase-activity.topic=" + W6GapReproductionAndRecoveryTest.TOPIC,
        "purchase-activity.group-id=" + W6GapReproductionAndRecoveryTest.GROUP,
        // streamer application.yml 의 test 프로파일 값과 같게. 이 context 는 commerce-api 의 yml 만 읽는다
        "purchase-activity.fetch-min-bytes=1",
        "purchase-activity.fetch-max-wait-ms=100",
        "purchase-activity.retry.max-retries=2",
        "purchase-activity.retry.initial-backoff-ms=100",
        "purchase-activity.retry.multiplier=2",
        "purchase-activity.dead-letter.send-timeout-ms=1000"
    }
)
class W6GapReproductionAndRecoveryTest {

    static final String TOPIC = "order-events-W6GapReproductionAndRecoveryTest";
    static final String GROUP = "purchase-activity-W6GapReproductionAndRecoveryTest";
    private static final TopicPartition PARTITION = new TopicPartition(TOPIC, 0);
    private static final Path OBSERVATION = Path.of("build", "w7-recovery", "observation.json");
    private static final String CARD_TYPE = "TEST";
    private static final String CARD_NO = "0000-0000-0000-0000";
    /** 고정 clock (결정 ⑤, W6 값 2026-09-07 과 다르게). 두 주문 모두 밀리초 절단값 2026-11-07T01:00:00.123Z 로 확정된다. */
    private static final Instant NOW = Instant.parse("2026-11-07T01:00:00.123456789Z");
    private static final Instant CONFIRMED_AT = Instant.parse("2026-11-07T01:00:00.123Z");
    private static final Duration CONSUME_DEADLINE = Duration.ofSeconds(30);

    static {
        KafkaTestContainersConfig.createTopic(TOPIC, 1);
    }

    @MockitoBean
    private Clock clock;

    @Autowired
    private W6DirectPublishPath w6Path;
    @Autowired
    private OrderFacade orderFacade;
    @Autowired
    private FakePaymentGateway fakePaymentGateway;
    @Autowired
    private FakeOrderConfirmedPublisher fakePublisher;
    @Autowired
    private KafkaOrderConfirmedPublisher kafkaPublisher;
    @Autowired
    private PurchaseActivityRepository purchaseActivityRepository;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private UserJpaRepository userJpaRepository;
    @Autowired
    private ProductJpaRepository productJpaRepository;
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
        fakePublisher.reset();
        given(clock.instant()).willReturn(NOW);
        given(clock.getZone()).willReturn(ZoneId.of("Asia/Seoul"));
    }

    @DisplayName("준비: W6 직접 발행 경로로 주문 A 는 commit 뒤 send 직전에 멈추고(구매 활동 0), 주문 B 는 실제 발행·소비까지 끝난다. PG create C=2, 외부 거래 T=2.")
    @Test
    void reproducesW6GapState_thenRecovers() throws IOException {
        // ======================= 준비 5-1 ~ 5-3 (📄 p.4) =======================
        // 고정 품목: 상품 A × 2, 상품 B × 1
        Long userId = userJpaRepository.save(new User()).getId();
        Long productA = saveProduct("상품 A", 1000L, 10);
        Long productB = saveProduct("상품 B", 2000L, 10);

        // ---- 주문 A: 확정 commit 뒤, send 직전 hook 에서 중단 (통제된 hook, 프로세스 종료가 아니다) ----
        Long orderAId = createOrder(userId, productA, productB);
        PaymentAttempt attemptA = startPayment(userId, orderAId, "w7-prep-a");
        fakePaymentGateway.markSuccess(attemptA.getTransactionKey());
        fakePublisher.delegateTo(kafkaPublisher);
        fakePublisher.beforeSend(event -> {
            throw new IllegalStateException("재현: 확정 commit 뒤 send 직전에 중단 (통제된 hook)");
        });

        OrderConfirmedEvent eventA = w6Path.confirmByCallback(attemptA.getProviderOrderId(), attemptA.getTransactionKey());

        // 주문 A 확인 - 새 조회: 확정·예약 소비는 됐고, 발행 호출은 1회 있었지만 broker 에는 record 가 없다
        assertThat(eventA.eventId()).isEqualTo(OrderConfirmedEvent.eventIdOf(orderAId));
        assertConfirmed(orderAId);
        assertThat(fakePublisher.publishCallCount()).isEqualTo(1);
        assertThat(fakePublisher.published()).isEmpty();
        assertThat(readAll()).isEmpty();

        // ---- 주문 B: hook 통과 → 실제 broker 발행 → 실제 streamer listener 소비 ----
        fakePublisher.beforeSend(event -> { });
        Long orderBId = createOrder(userId, productA, productB);
        PaymentAttempt attemptB = startPayment(userId, orderBId, "w7-prep-b");
        fakePaymentGateway.markSuccess(attemptB.getTransactionKey());

        OrderConfirmedEvent eventB = w6Path.confirmByCallback(attemptB.getProviderOrderId(), attemptB.getTransactionKey());

        assertThat(eventB.eventId()).isEqualTo(OrderConfirmedEvent.eventIdOf(orderBId));
        assertConfirmed(orderBId);
        assertThat(fakePublisher.published()).hasSize(1);
        List<ConsumerRecord<String, byte[]>> records = readAll();
        assertThat(records).hasSize(1);
        ConsumerRecord<String, byte[]> recordB = records.get(0);
        assertThat(recordB.key()).isEqualTo(String.valueOf(orderBId));
        JsonNode valueB = objectMapper.readTree(recordB.value());
        assertThat(valueB.get("eventId").asText()).isEqualTo(eventB.eventId());
        assertThat(valueB.get("confirmedAt").asText()).isEqualTo(CONFIRMED_AT.toString());

        // 실제 listener 가 B 를 처리해 ack(committed offset 전진)할 때까지 deadline 안에서 기다린다
        awaitConsumed(recordB.offset() + 1);

        // 구매 활동 확인 - 새 DB 조회: A 는 0행, B 는 품목대로 (상품 A × 2, 상품 B × 1)
        assertThat(purchaseActivityRepository.findProcessedByOrderId(orderAId)).isEmpty();
        assertThat(purchaseActivityRepository.findActivitiesByOrderId(orderAId)).isEmpty();
        ProcessedOrderConfirmation processedB = purchaseActivityRepository.findProcessedByOrderId(orderBId).orElseThrow();
        assertThat(processedB.getEventId()).isEqualTo(eventB.eventId());
        assertThat(processedB.getConfirmedAt().toInstant()).isEqualTo(CONFIRMED_AT);
        List<ProductPurchaseActivity> activitiesB = purchaseActivityRepository.findActivitiesByOrderId(orderBId);
        assertThat(activitiesB).hasSize(2);
        assertThat(activitiesB.get(0).getProductId()).isEqualTo(productA);
        assertThat(activitiesB.get(0).getQuantity()).isEqualTo(2);
        assertThat(activitiesB.get(1).getProductId()).isEqualTo(productB);
        assertThat(activitiesB.get(1).getQuantity()).isEqualTo(1);

        // PG 호출 수 - 준비 기준값 (📄 p.5 "두 주문을 각 한 번 시작한 최소 사례라면 C=2, T=2")
        int pgCreateCount = fakePaymentGateway.createCount();
        List<PaymentTransaction> pgTransactions = fakePaymentGateway.transactions();
        assertThat(pgCreateCount).isEqualTo(2);
        assertThat(pgTransactions).hasSize(2);

        // TODO(W7 Outbox 생성 후): 두 주문 모두 Outbox 행이 아직 없는지 새 DB 조회로 확인 (📄 p.4 5-2 "Outbox는 아직 없으며")

        // 이번 실행의 사실 기록 (W6 기록과 별도)
        Map<String, Object> observation = new LinkedHashMap<>();
        observation.put("phase", "prepare (5-1 ~ 5-3)");
        observation.put("fixedClock", NOW.toString());
        observation.put("topic", TOPIC);
        observation.put("consumerGroup", GROUP);
        observation.put("orders", List.of(
            observe("A: commit 후 send 전 중단", orderAId, eventA, null, 0),
            observe("B: 발행·소비 완료", orderBId, eventB, recordB, activitiesB.size())));
        observation.put("pgCreateCount", pgCreateCount);
        observation.put("pgTransactionCount", pgTransactions.size());
        observation.put("pgTransactionKeys", pgTransactions.stream().map(PaymentTransaction::transactionKey).toList());
        Files.createDirectories(OBSERVATION.getParent());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(OBSERVATION.toFile(), observation);

        // ======================= TODO 7단계: 복구 5-4 ~ 5-6 (📄 p.4~5) =======================
        // 같은 DB·broker·PG 를 그대로 둔 채, W7 Outbox 저장 구조·복구 경로·단일 relay 를 여기서 이어 붙인다.
        // - 5-4: 직접 발행 중단. SUCCESS 직접 삽입·Outbox 행 삭제·운영용 우회 스위치 없이
        // - 5-5: A·B 의 저장된 확정 사실(eventId·confirmedAt·items)로 Outbox 가 없을 때만 한 행씩 추가, 있으면 내용 일치 확인
        // - 5-6: relay + 기존 consumer 실행 → A 의 구매 활동 생성 / B 의 활동 행·수량 불변 / 두 번째 복구도 Outbox·구매 효과 불변
        // - 복구 중 PG create = pgCreateCount(C), 외부 거래 수 = pgTransactionCount(T), 조회·callback 추가 호출 0, 재확정·예약 추가 소비 0

        // ======================= 정리 (복구까지 모두 끝난 뒤에만) =======================
        cleanUpOwnResources();
    }

    /** 자신이 만든 DB 행만 정리한다. 중간에 끼우지 않고 테스트 맨 끝에서만 호출한다 (📄 p.4 5-3, p.5 "모든 확인 뒤에만"). */
    private void cleanUpOwnResources() {
        databaseCleanUp.truncateAllTables();
    }

    private void assertConfirmed(Long orderId) {
        Order order = orderJpaRepository.findById(orderId).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(Order.OrderStatus.CONFIRMED);
        assertThat(order.getConfirmedAt().toInstant()).isEqualTo(CONFIRMED_AT);
        assertThat(attemptOf(orderId).getStatus()).isEqualTo(PaymentAttempt.Status.SUCCESS);
        assertThat(reservationStatusesOf(orderId)).isNotEmpty().containsOnly(StockReservation.Status.CONSUMED);
    }

    /** 실제 listener 가 ack 해 이 group 의 committed offset 이 기대값에 닿을 때까지 기다린다. deadline 을 넘기면 실제 상태와 함께 실패한다. */
    private void awaitConsumed(long expectedCommittedOffset) {
        try (AdminClient admin = AdminClient.create(
            Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaTestContainersConfig.bootstrapServers()))) {
            await().atMost(CONSUME_DEADLINE).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
                OffsetAndMetadata committed = admin.listConsumerGroupOffsets(GROUP)
                    .partitionsToOffsetAndMetadata().get(10, TimeUnit.SECONDS).get(PARTITION);
                assertThat(committed).as("group %s 의 committed offset 이 아직 없다", GROUP).isNotNull();
                assertThat(committed.offset()).isEqualTo(expectedCommittedOffset);
            });
        }
    }

    /** 기록은 전부 새 DB 조회값이다. 확정 전에 들고 있던 엔티티 객체를 쓰지 않는다. */
    private Map<String, Object> observe(String role, Long orderId, OrderConfirmedEvent event, ConsumerRecord<String, byte[]> record, int activityRows) {
        Order order = orderJpaRepository.findWithItemsById(orderId).orElseThrow();
        PaymentAttempt attempt = attemptOf(orderId);
        Map<String, Object> observation = new LinkedHashMap<>();
        observation.put("role", role);
        observation.put("orderId", orderId);
        observation.put("eventId", event.eventId());
        observation.put("confirmedAt", order.getConfirmedAt().toInstant().toString());
        observation.put("items", order.getItems().stream()
            .map(item -> Map.of("productId", item.getProductId(), "quantity", item.getQuantity()))
            .toList());
        observation.put("providerOrderId", attempt.getProviderOrderId());
        observation.put("pgTransactionKey", attempt.getTransactionKey());
        observation.put("orderStatus", order.getStatus().name());
        observation.put("attemptStatus", attempt.getStatus().name());
        observation.put("reservationStatuses", reservationStatusesOf(orderId).stream().map(Enum::name).toList());
        observation.put("brokerRecord", record == null ? "없음 (send 직전 중단, 발행되지 않음)" : Map.of(
            "topic", record.topic(), "partition", record.partition(), "offset", record.offset(),
            "key", record.key(), "value", new String(record.value(), StandardCharsets.UTF_8)));
        observation.put("purchaseActivityRows", activityRows);
        return observation;
    }

    /** 이 클래스 전용 topic 의 partition 0 을 처음부터 끝까지 읽는다 (listener 와 다른 group, offset 을 commit 하지 않는다). */
    private List<ConsumerRecord<String, byte[]>> readAll() {
        Map<String, Object> props = Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaTestContainersConfig.bootstrapServers(),
            ConsumerConfig.GROUP_ID_CONFIG, "w7-recovery-reader-" + UUID.randomUUID(),
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

    private Long createOrder(Long userId, Long productA, Long productB) {
        return orderFacade.createOrder(userId, List.of(new OrderCommand.Item(productA, 2), new OrderCommand.Item(productB, 1))).orderId();
    }

    private PaymentAttempt startPayment(Long userId, Long orderId, String attemptKey) {
        orderFacade.confirmOrder(userId, orderId, new OrderCommand.Confirm(attemptKey, CARD_TYPE, CARD_NO));
        return attemptOf(orderId);
    }

    private PaymentAttempt attemptOf(Long orderId) {
        return paymentAttemptJpaRepository.findByOrderId(orderId).orElseThrow();
    }

    private List<StockReservation.Status> reservationStatusesOf(Long orderId) {
        return stockReservationJpaRepository.findAllByOrderId(orderId).stream().map(StockReservation::getStatus).toList();
    }
}
