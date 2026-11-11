package com.loopers.crossapp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.order.OrderCommand;
import com.loopers.application.order.OrderConfirmedOutboxBackfill;
import com.loopers.application.order.OrderConfirmedOutboxRelay;
import com.loopers.application.order.OrderFacade;
import com.loopers.application.order.OutboxRelayResult;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderConfirmedEvent;
import com.loopers.domain.order.OrderConfirmedOutbox;
import com.loopers.domain.order.OrderConfirmedOutboxRepository;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

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
import static org.mockito.Mockito.mockingDetails;

/**
 * 7주차 과제 5절 "새 격리 실행에서 W6 누락 상태를 재현하고 복구하기" (📄 p.4~5).
 * 준비 5-1 ~ 5-3 과 복구 5-4 ~ 5-6 을 한 테스트 메서드에서 이어서 실행한다. 사이에 DB·broker·PG 초기화가 없다.
 *
 * <p>복구 (설계 D34~D36): 같은 DB 에서 A·B 의 저장된 확정 사실로 Outbox 를 backfill → 단일 relay 한 회차 → 기존 consumer.
 * A 는 빠졌던 구매 활동이 생기고, B 는 재발행이 와도 장부·활동 불변. 두 번째 복구는 Outbox·구매 효과를 늘리지 않는다.
 * 복구 중 PG create·조회·callback 추가 호출 0, 주문 재확정·예약 추가 소비 0 을 기준값과 비교한다.
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
    /** streamer 장부 저장소. spy 로 두어 consumer 가 장부를 새로 저장한 횟수를 센다 (D36). */
    @MockitoSpyBean
    private PurchaseActivityRepository purchaseActivityRepository;
    @Autowired
    private OrderConfirmedOutboxRepository outboxRepository;
    @Autowired
    private OrderConfirmedOutboxBackfill backfill;
    @Autowired
    private OrderConfirmedOutboxRelay relay;
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

    @DisplayName("준비: W6 경로로 A 는 commit 뒤 send 직전 중단(구매 활동 0), B 는 발행·소비 완료, C=2·T=2. 복구: backfill → relay → consumer 로 A 의 활동이 생기고 B 는 불변, 두 번째 복구는 효과 0, PG 추가 호출 0.")
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
        assertThat(valueB.get("orderId").asLong()).isEqualTo(orderBId);
        assertThat(valueB.get("confirmedAt").asText()).isEqualTo(CONFIRMED_AT.toString());
        assertThat(valueB.get("items")).hasSize(2);
        assertThat(valueB.get("items").get(0).get("productId").asLong()).isEqualTo(productA);
        assertThat(valueB.get("items").get(0).get("quantity").asInt()).isEqualTo(2);
        assertThat(valueB.get("items").get(1).get("productId").asLong()).isEqualTo(productB);
        assertThat(valueB.get("items").get(1).get("quantity").asInt()).isEqualTo(1);

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

        // W6 경로는 Outbox 를 쓰지 않으므로 두 주문 모두 Outbox 행이 아직 없다 - 새 DB 조회 (📄 p.4 5-2 "Outbox는 아직 없으며")
        assertThat(outboxRepository.findByOrderId(orderAId)).isEmpty();
        assertThat(outboxRepository.findByOrderId(orderBId)).isEmpty();
        assertThat(outboxRepository.findByEventId(eventA.eventId())).isEmpty();
        assertThat(outboxRepository.findByEventId(eventB.eventId())).isEmpty();

        // 준비 기록 (W6 기록과 별도). 파일은 복구까지 끝난 뒤 한 번 쓴다 (D41)
        Map<String, Object> prepare = new LinkedHashMap<>();
        prepare.put("phase", "prepare (5-1 ~ 5-3)");
        prepare.put("fixedClock", NOW.toString());
        prepare.put("topic", TOPIC);
        prepare.put("consumerGroup", GROUP);
        prepare.put("orders", List.of(
            observe("A: commit 후 send 전 중단", orderAId, eventA, null, 0),
            observe("B: 발행·소비 완료", orderBId, eventB, recordB, activitiesB.size())));
        prepare.put("pgCreateCount", pgCreateCount);
        prepare.put("pgTransactionCount", pgTransactions.size());
        prepare.put("pgTransactionKeys", pgTransactions.stream().map(PaymentTransaction::transactionKey).toList());

        // ======================= 복구 5-4 ~ 5-6 (📄 p.4~5, 설계 D34~D36) =======================
        // 5-4: 제품 경로의 직접 발행은 이미 제거됐다(D17). 같은 DB·broker·PG 를 그대로 둔 채 W7 의 Outbox·backfill·relay 를 연결한다.
        //      SUCCESS 직접 삽입·Outbox 삭제·우회 스위치 없음. DB·broker·PG 초기화 없음.
        // 복구 직전 기준값 (📄 p.5 "복구 시작 직전 기준값을 기록하고 이후 증가가 0인지 확인합니다")
        int pgCreateBefore = fakePaymentGateway.createCount();
        int pgLookupBefore = fakePaymentGateway.lookupCallCount();
        int pgTxBefore = fakePaymentGateway.transactions().size();
        long committedBefore = committedOffset();
        long savedBefore = saveProcessedCalls();
        Order orderABefore = orderJpaRepository.findById(orderAId).orElseThrow();
        Order orderBBefore = orderJpaRepository.findById(orderBId).orElseThrow();
        int stockABefore = stockOf(productA);
        int stockBBefore = stockOf(productB);
        ProcessedOrderConfirmation ledgerBBefore = purchaseActivityRepository.findProcessedByOrderId(orderBId).orElseThrow();
        List<Long> activityBIdsBefore = activitiesB.stream().map(ProductPurchaseActivity::getId).toList();

        // 5-5: 저장된 확정 사실로 Outbox 가 없을 때만 한 행씩 추가 (📄 p.4 5-5)
        assertThat(backfill.backfill(orderAId)).isEqualTo(OrderConfirmedOutboxBackfill.Result.CREATED);
        assertThat(backfill.backfill(orderBId)).isEqualTo(OrderConfirmedOutboxBackfill.Result.CREATED);
        OrderConfirmedOutbox outboxA = outboxRepository.findByOrderId(orderAId).orElseThrow();
        OrderConfirmedOutbox outboxB = outboxRepository.findByOrderId(orderBId).orElseThrow();
        // backfill 이 다시 만든 payload 는 W6 경로가 발행했던 이벤트 객체(eventA·eventB)를 그대로 직렬화한 것과 같다
        assertThat(outboxA.getEventId()).isEqualTo(eventA.eventId());
        assertThat(outboxA.getPayload()).isEqualTo(objectMapper.writeValueAsString(eventA));
        assertThat(outboxA.getStatus()).isEqualTo(OrderConfirmedOutbox.Status.PENDING);
        assertThat(outboxB.getEventId()).isEqualTo(eventB.eventId());
        assertThat(outboxB.getPayload()).isEqualTo(objectMapper.writeValueAsString(eventB));
        assertThat(new String(recordB.value(), StandardCharsets.UTF_8)).isEqualTo(outboxB.getPayload()); // W6 가 보낸 bytes 와도 같다
        assertThat(outboxB.getStatus()).isEqualTo(OrderConfirmedOutbox.Status.PENDING);

        // 5-6: relay + 기존 consumer (📄 p.4 5-6)
        OutboxRelayResult recoveryRun = relay.runOnce();
        assertThat(recoveryRun.reason()).isEqualTo(OutboxRelayResult.Reason.COMPLETED);
        assertThat(recoveryRun.picked()).isEqualTo(2);
        assertThat(recoveryRun.published()).isEqualTo(2);
        assertThat(recoveryRun.skippedStale()).isZero();
        outboxA = outboxRepository.findByOrderId(orderAId).orElseThrow();
        outboxB = outboxRepository.findByOrderId(orderBId).orElseThrow();
        assertThat(outboxA.getStatus()).isEqualTo(OrderConfirmedOutbox.Status.PUBLISHED);
        assertThat(outboxB.getStatus()).isEqualTo(OrderConfirmedOutbox.Status.PUBLISHED);
        assertThat(outboxA.getAttemptCount()).isEqualTo(1);
        assertThat(outboxB.getAttemptCount()).isEqualTo(1);

        // broker: A 의 eventId record 1개(처음 발행), B 의 eventId record 2개(W6 발행 + 재발행, 다른 offset, 같은 bytes)
        List<ConsumerRecord<String, byte[]>> afterRecovery = readAll();
        List<ConsumerRecord<String, byte[]>> recordsA = recordsOf(afterRecovery, orderAId);
        List<ConsumerRecord<String, byte[]>> recordsBAll = recordsOf(afterRecovery, orderBId);
        assertThat(afterRecovery).hasSize(3);
        assertThat(recordsA).hasSize(1);
        assertThat(new String(recordsA.get(0).value(), StandardCharsets.UTF_8)).isEqualTo(outboxA.getPayload());
        assertThat(recordsBAll).hasSize(2);
        assertThat(recordsBAll.get(0).offset()).isEqualTo(recordB.offset());
        assertThat(recordsBAll.get(1).offset()).isGreaterThan(recordB.offset());
        assertThat(recordsBAll.get(1).value()).isEqualTo(recordB.value());
        long lastOffset = afterRecovery.get(afterRecovery.size() - 1).offset();
        awaitConsumed(lastOffset + 1);

        // A: 빠졌던 구매 활동이 생긴다. B: 재발행이 와도 장부·활동 행·수량 불변
        ProcessedOrderConfirmation processedA = purchaseActivityRepository.findProcessedByOrderId(orderAId).orElseThrow();
        assertThat(processedA.getEventId()).isEqualTo(eventA.eventId());
        List<ProductPurchaseActivity> activitiesA = purchaseActivityRepository.findActivitiesByOrderId(orderAId);
        assertThat(activitiesA).hasSize(2);
        assertThat(activitiesA.get(0).getProductId()).isEqualTo(productA);
        assertThat(activitiesA.get(0).getQuantity()).isEqualTo(2);
        assertThat(activitiesA.get(1).getProductId()).isEqualTo(productB);
        assertThat(activitiesA.get(1).getQuantity()).isEqualTo(1);
        assertActivitiesUnchanged(orderBId, ledgerBBefore, activityBIdsBefore);
        assertThat(saveProcessedCalls()).isEqualTo(savedBefore + 1); // A 의 장부 1건만 새로 저장, B 는 no-op

        // 복구 중 PG 호출·재확정·예약 추가 소비 0 (📄 p.5 "PG create = C, PG 조회·callback 추가 호출 = 0 / 외부 거래 수 = T, 주문 재확정·예약 추가 소비 = 0")
        assertThat(fakePaymentGateway.createCount()).isEqualTo(pgCreateBefore).isEqualTo(pgCreateCount);
        assertThat(fakePaymentGateway.lookupCallCount()).isEqualTo(pgLookupBefore);
        assertThat(fakePaymentGateway.transactions()).hasSize(pgTxBefore).hasSize(pgTransactions.size());
        assertOrderUntouched(orderAId, orderABefore);
        assertOrderUntouched(orderBId, orderBBefore);
        assertThat(stockOf(productA)).isEqualTo(stockABefore);
        assertThat(stockOf(productB)).isEqualTo(stockBBefore);

        // 같은 대상의 두 번째 복구: Outbox·구매 효과를 늘리지 않는다 (📄 p.4 5-6)
        long committedAfterFirst = committedOffset();
        long savedAfterFirst = saveProcessedCalls();
        assertThat(backfill.backfill(orderAId)).isEqualTo(OrderConfirmedOutboxBackfill.Result.ALREADY_PRESENT);
        assertThat(backfill.backfill(orderBId)).isEqualTo(OrderConfirmedOutboxBackfill.Result.ALREADY_PRESENT);
        OutboxRelayResult secondRun = relay.runOnce();
        assertThat(secondRun.picked()).isZero();
        assertThat(secondRun.remainingPending()).isZero();
        assertThat(outboxRepository.findByOrderId(orderAId).orElseThrow().getStatus()).isEqualTo(OrderConfirmedOutbox.Status.PUBLISHED);
        assertThat(outboxRepository.findByOrderId(orderBId).orElseThrow().getPayload()).isEqualTo(outboxB.getPayload());
        assertThat(readAll()).hasSize(3);
        assertThat(committedOffset()).isEqualTo(committedAfterFirst);
        assertThat(saveProcessedCalls()).isEqualTo(savedAfterFirst);
        assertActivitiesUnchanged(orderBId, ledgerBBefore, activityBIdsBefore);
        assertThat(purchaseActivityRepository.findActivitiesByOrderId(orderAId)).hasSize(2);
        assertThat(fakePaymentGateway.createCount()).isEqualTo(pgCreateBefore);
        assertThat(fakePaymentGateway.transactions()).hasSize(pgTxBefore);

        // 복구 기록 (D41)
        Map<String, Object> recovery = new LinkedHashMap<>();
        recovery.put("phase", "recovery (5-4 ~ 5-6)");
        recovery.put("baseline", Map.of(
            "pgCreateCount", pgCreateBefore, "pgLookupCallCount", pgLookupBefore, "pgTransactionCount", pgTxBefore,
            "consumerCommittedOffset", committedBefore, "ledgerSaveCalls", savedBefore));
        recovery.put("backfill", Map.of("A", "CREATED", "B", "CREATED"));
        recovery.put("relayRun", relayResultOf(recoveryRun));
        recovery.put("outbox", List.of(outboxObservation(outboxA, recordsA.get(0)), outboxObservation(outboxB, recordsBAll.get(1))));
        recovery.put("brokerRecordsByOrder", Map.of(
            "A", recordsA.stream().map(ConsumerRecord::offset).toList(),
            "B", recordsBAll.stream().map(ConsumerRecord::offset).toList()));
        recovery.put("consumer", Map.of(
            "committedOffsetAfter", committedAfterFirst, "ledgerSaveCallsAfter", savedAfterFirst,
            "purchaseActivityRowsA", activitiesA.size(), "purchaseActivityRowsB", activityBIdsBefore.size()));
        recovery.put("after", Map.of(
            "pgCreateCount", fakePaymentGateway.createCount(), "pgLookupCallCount", fakePaymentGateway.lookupCallCount(),
            "pgTransactionCount", fakePaymentGateway.transactions().size()));
        recovery.put("secondRecovery", Map.of(
            "backfill", Map.of("A", "ALREADY_PRESENT", "B", "ALREADY_PRESENT"),
            "relayRun", relayResultOf(secondRun),
            "committedOffset", committedOffset(), "ledgerSaveCalls", saveProcessedCalls(), "brokerRecords", 3));

        Map<String, Object> observation = new LinkedHashMap<>();
        observation.put("prepare", prepare);
        observation.put("recovery", recovery);
        Files.createDirectories(OBSERVATION.getParent());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(OBSERVATION.toFile(), observation);

        // ======================= 정리 (복구까지 모두 끝난 뒤에만) =======================
        cleanUpOwnResources();
    }

    private void assertOrderUntouched(Long orderId, Order before) {
        Order after = orderJpaRepository.findById(orderId).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(Order.OrderStatus.CONFIRMED);
        assertThat(after.getConfirmedAt()).isEqualTo(before.getConfirmedAt());
        assertThat(after.getUpdatedAt()).isEqualTo(before.getUpdatedAt());
        assertThat(attemptOf(orderId).getStatus()).isEqualTo(PaymentAttempt.Status.SUCCESS);
        assertThat(reservationStatusesOf(orderId)).containsOnly(StockReservation.Status.CONSUMED);
    }

    private void assertActivitiesUnchanged(Long orderId, ProcessedOrderConfirmation ledgerBefore, List<Long> activityIdsBefore) {
        ProcessedOrderConfirmation ledger = purchaseActivityRepository.findProcessedByOrderId(orderId).orElseThrow();
        assertThat(ledger.getId()).isEqualTo(ledgerBefore.getId());
        assertThat(ledger.getProcessedAt()).isEqualTo(ledgerBefore.getProcessedAt());
        List<ProductPurchaseActivity> activities = purchaseActivityRepository.findActivitiesByOrderId(orderId);
        assertThat(activities.stream().map(ProductPurchaseActivity::getId).toList()).isEqualTo(activityIdsBefore);
        assertThat(activities.get(0).getQuantity()).isEqualTo(2);
        assertThat(activities.get(1).getQuantity()).isEqualTo(1);
    }

    private static List<ConsumerRecord<String, byte[]>> recordsOf(List<ConsumerRecord<String, byte[]>> records, Long orderId) {
        return records.stream().filter(r -> String.valueOf(orderId).equals(r.key())).toList();
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

    /** streamer 장부 저장(saveProcessed) 호출 수. consumer 가 새 사건을 저장했는지 세는 데 쓴다. */
    private long saveProcessedCalls() {
        return mockingDetails(purchaseActivityRepository).getInvocations().stream()
            .filter(invocation -> invocation.getMethod().getName().equals("saveProcessed"))
            .count();
    }

    private int stockOf(Long productId) {
        return productJpaRepository.findById(productId).orElseThrow().getStock().getQuantity();
    }

    private static Map<String, Object> relayResultOf(OutboxRelayResult result) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("reason", result.reason().name());
        map.put("picked", result.picked());
        map.put("published", result.published());
        map.put("retried", result.retried());
        map.put("blocked", result.blocked());
        map.put("skippedStale", result.skippedStale());
        map.put("deferred", result.deferred());
        map.put("remainingPending", result.remainingPending());
        return map;
    }

    private static Map<String, Object> outboxObservation(OrderConfirmedOutbox outbox, ConsumerRecord<String, byte[]> relayRecord) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("eventId", outbox.getEventId());
        map.put("orderId", outbox.getOrderId());
        map.put("status", outbox.getStatus().name());
        map.put("attemptCount", outbox.getAttemptCount());
        map.put("publishedAt", outbox.getPublishedAt() == null ? null : outbox.getPublishedAt().toInstant().toString());
        map.put("relayRecord", Map.of("topic", relayRecord.topic(), "partition", relayRecord.partition(), "offset", relayRecord.offset()));
        return map;
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
        observation.put("outboxRows", outboxRepository.findByOrderId(orderId).isPresent() ? 1 : 0);
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
