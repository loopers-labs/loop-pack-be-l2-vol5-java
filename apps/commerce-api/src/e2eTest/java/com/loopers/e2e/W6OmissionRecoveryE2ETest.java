package com.loopers.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.CommerceApiApplication;
import com.loopers.application.order.OrderOutboxBackfill;
import com.loopers.application.order.OrderOutboxRelay;
import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.order.OrderConfirmedEvent;
import com.loopers.domain.order.OrderOutboxModel;
import com.loopers.domain.order.OrderOutboxService;
import com.loopers.domain.order.OrderOutboxStatus;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.order.ReservationStatus;
import com.loopers.domain.payment.ProviderOrderId;
import com.loopers.domain.point.PointModel;
import com.loopers.domain.product.ProductModel;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.order.KafkaOrderEventPublisher;
import com.loopers.infrastructure.order.OrderJpaRepository;
import com.loopers.infrastructure.order.OrderOutboxJpaRepository;
import com.loopers.infrastructure.order.StockReservationJpaRepository;
import com.loopers.infrastructure.point.PointJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.interfaces.consumer.OrderConfirmedListener;
import com.loopers.support.kafka.BrokerRecords;
import com.loopers.support.kafka.RecordingOrderEventPublisher;
import com.loopers.support.kafka.W6DirectPublishConfig;
import com.loopers.support.pg.PaymentTestSupport;
import com.loopers.support.pg.TestPgResetExtension;
import com.loopers.support.pg.TestPgServer;
import com.loopers.support.pg.TestPgStatus;
import com.loopers.support.pg.TestPgTransaction;
import com.loopers.testcontainers.KafkaBrokerFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.filter.TypeExcludeFilters;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 새 격리 실행에서 W6 누락 상태를 재현하고, 같은 실행 안에서 W7 경로로 복구한다 (W7 과제 5절, docs/week7/design.md 4-4절).
 * 역사적 원본 행의 복원이 아니라 "W6 누락 상태 재현 → 같은 실행 안에서 복구"다. 이번 실행의 사건이고 W6 기록과는 별개다.
 *
 * <h3>준비 (W6 경로)</h3>
 * 보존한 W6 테스트 구성({@link W6DirectPublishConfig})으로 실제 주문 API·로컬 PG·callback 확정과 commit 뒤 직접 발행을 실행한다.
 * W6의 고정 입력을 쓴다: 확정 시각 {@value #W6_CONFIRMED_AT}, 상품 하나(단가 7,000원, 재고 5)를 주문마다 수량 2.
 * <ul>
 *   <li>사례 A (미발행): 확정 commit 뒤 send 직전에서 멈춘다(발행기 주입, OS 강제 종료 아님). broker record·구매 활동이 없다.</li>
 *   <li>사례 B (발행·소비 완료): W6의 실제 발행기로 broker에 보내고 실제 consumer가 구매 활동을 만든다.</li>
 * </ul>
 *
 * <h3>복구 (W7 경로, 같은 DB·broker·PG fixture)</h3>
 * 직접 발행을 멈추고 W7의 누락 복구({@link OrderOutboxBackfill})와 단일 relay를 잇는다. 대상은 A·B 두 주문뿐이다.
 * SUCCESS를 직접 넣거나 Outbox 행을 지우거나 제품에 우회 스위치를 두지 않는다. 준비와 복구 사이에 DB·broker·PG를 초기화하지 않는다.
 */
@SpringBootTest(classes = CommerceApiApplication.class)
@AutoConfigureMockMvc
@TypeExcludeFilters(ExcludeStreamerFromScan.class)
@Import({PurchaseActivityConsumerConfig.class, W6DirectPublishConfig.class, W6OmissionRecoveryE2ETest.RecoveryClockConfig.class})
@ExtendWith({TestPgResetExtension.class, OutputCaptureExtension.class})
class W6OmissionRecoveryE2ETest {

    static final String W6_CONFIRMED_AT = "2026-09-07T01:00:00Z";
    private static final Instant RECOVERY_AT = Instant.parse(W6_CONFIRMED_AT).plus(Duration.ofDays(3));

    private static final Logger log = LoggerFactory.getLogger(W6OmissionRecoveryE2ETest.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TOPIC = KafkaBrokerFixture.createTopic("order-events-w6-recovery", 1);
    private static final String GROUP = KafkaBrokerFixture.uniqueName("purchase-activity-w6-recovery");
    private static final Duration DEADLINE = Duration.ofSeconds(60);

    private static final long USER_ID = 70_002L;
    private static final long INITIAL_BALANCE = 100_000L;
    private static final int INITIAL_STOCK = 5;
    private static final int ORDER_QUANTITY = 2;
    private static final long UNIT_PRICE = 7_000L;

    @TestConfiguration
    static class RecoveryClockConfig {

        @Bean
        @Primary
        SettableClock recoveryClock() {
            return new SettableClock(Instant.parse(W6_CONFIRMED_AT));
        }
    }

    @DynamicPropertySource
    static void kafka(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KafkaBrokerFixture::bootstrapServers);
        registry.add("order-event.topic-name", () -> TOPIC);
        registry.add("order-event.consumer-group", () -> GROUP);
        registry.add("order-event.listener-auto-startup", () -> "false");
    }

    /** 이번 재현 실행에서 기록한 사례의 사실. 복구가 이 값을 그대로 보존하는지 비교한다. 카드 정보는 담지 않는다. */
    private record CaseFacts(Long orderId, String eventId, String confirmedAt, List<OrderConfirmedEvent.Item> items,
                             String providerOrderId, String transactionKey) {
    }

    /** PG fixture의 누적 호출 수. 준비와 복구를 나눠 읽는다. */
    private record PgCalls(int creates, int transactions, int lookupsByKey, int lookupsByOrder, int requests) {
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private SettableClock clock;

    @Autowired
    private OrderOutboxService orderOutboxService;

    @Autowired
    private RecordingOrderEventPublisher w6Publisher;

    @Autowired
    private KafkaOrderEventPublisher w6KafkaPublisher;

    @Autowired
    private OrderOutboxBackfill backfill;

    @Autowired
    private OrderOutboxRelay relay;

    @Autowired
    private BrandJpaRepository brandJpaRepository;

    @Autowired
    private ProductJpaRepository productJpaRepository;

    @Autowired
    private PointJpaRepository pointJpaRepository;

    @Autowired
    private OrderJpaRepository orderJpaRepository;

    @Autowired
    private StockReservationJpaRepository stockReservationJpaRepository;

    @Autowired
    private OrderOutboxJpaRepository orderOutboxJpaRepository;

    @Autowired
    private KafkaListenerEndpointRegistry listenerRegistry;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final TestPgServer pg = TestPgServer.shared();

    @AfterEach
    void stopOwnResources() {
        // 모든 확인이 끝난 뒤에만, 이 테스트가 시작한 consumer와 발행기 설정만 정리한다 (PG fixture는 TestPgResetExtension이 되돌린다)
        listenerRegistry.getListenerContainer(OrderConfirmedListener.LISTENER_ID).stop();
        w6Publisher.clear();
    }

    @DisplayName("W6 경로로 만든 미발행 주문 A와 발행·소비가 끝난 주문 B를, 같은 실행에서 저장된 확정 사실로 Outbox를 채워 relay로 보내면 "
        + "A의 구매 활동이 생기고 B는 늘지 않으며, PG·주문·예약은 그대로이고 두 번째 복구도 아무것도 늘리지 않는다.")
    @Test
    void reproducesW6Omission_thenRecoversInSameRun(CapturedOutput output) throws Exception {
        // ======== 준비: W6 경로 ========
        W6DirectPublishConfig.DirectPublishingOutboxService w6Path = (W6DirectPublishConfig.DirectPublishingOutboxService) orderOutboxService;
        assertThat(w6Path.isDirectPublishing()).as("준비는 W6의 commit 뒤 직접 발행 경로로 한다").isTrue();
        BrandModel brand = brandJpaRepository.save(new BrandModel("w6-repro-brand"));
        ProductModel product = productJpaRepository.save(new ProductModel(brand.getId(), "w6-repro-product", UNIT_PRICE, INITIAL_STOCK));
        PointModel point = new PointModel(USER_ID);
        point.charge(INITIAL_BALANCE);
        pointJpaRepository.save(point);
        w6Publisher.clear();
        w6Publisher.delegateTo(w6KafkaPublisher);
        MessageListenerContainer consumer = listenerRegistry.getListenerContainer(OrderConfirmedListener.LISTENER_ID);
        consumer.start();
        await().atMost(DEADLINE).until(consumer::isRunning);

        Long orderA = E2eSupport.createOrder(mvc, USER_ID, Map.of(product.getId(), ORDER_QUANTITY));
        Long orderB = E2eSupport.createOrder(mvc, USER_ID, Map.of(product.getId(), ORDER_QUANTITY));

        // 사례 A: 검증한 PG SUCCESS의 확정 commit 뒤, send 직전에서 멈춘다
        w6Publisher.failWith("commit 뒤 send 직전에 중단(주입, OS 강제 종료 아님)");
        TestPgTransaction transactionA = confirmThroughCallback(orderA);
        // 사례 B: 같은 경로로 확정하고 실제 broker로 발행한다
        w6Publisher.failWith(null);
        TestPgTransaction transactionB = confirmThroughCallback(orderB);
        await().atMost(DEADLINE).untilAsserted(() -> assertThat(E2eSupport.committedOffset(GROUP, TOPIC)).as("B 소비 완료").isEqualTo(1L));

        // 준비 결과(새 DB 조회): 두 주문 모두 확정·예약 소비, Outbox 없음, A만 구매 활동 0
        for (Long id : List.of(orderA, orderB)) {
            assertThat(orderJpaRepository.findById(id).orElseThrow().getStatus()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(stockReservationJpaRepository.findByOrderId(id).orElseThrow().getStatus()).isEqualTo(ReservationStatus.CONSUMED);
        }
        assertThat(orderOutboxJpaRepository.count()).as("W6 경로에는 다시 찾을 Outbox 행이 없다").isZero();
        assertThat(E2eSupport.purchaseActivities(jdbcTemplate, orderA)).as("A: 구매 활동 누락").isEmpty();
        assertThat(E2eSupport.purchaseActivities(jdbcTemplate, orderB)).containsExactly(tuple(product.getId(), ORDER_QUANTITY));
        List<BrokerRecords.Record> w6Records = BrokerRecords.readAll(TOPIC);
        assertThat(w6Records).extracting(BrokerRecords.Record::key).as("broker에는 B의 record만 있다").containsExactly(String.valueOf(orderB));
        String w6RecordOfB = w6Records.get(0).value();

        // 이번 실행의 사건 사실을 기록한다 (W6가 그때 만든 이벤트·DB의 확정 시각·PG 거래)
        CaseFacts factsA = factsOf(orderA, transactionA);
        CaseFacts factsB = factsOf(orderB, transactionB);
        PgCalls prepared = pgCalls();
        assertThat(prepared.creates()).as("C: 두 주문을 각각 한 번 시작").isEqualTo(2);
        assertThat(prepared.transactions()).as("T: 외부 거래 수").isEqualTo(2);
        int stockBeforeRecovery = stockOf(product);
        int w6PublishAttempts = w6Publisher.attempts().size();
        log.info("[W6 재현] A={} | B={} | PG 준비 {}", factsA, factsB, prepared);

        // ======== 복구: 같은 DB에 W7 경로를 잇는다 ========
        w6Path.stopDirectPublishing();
        clock.set(RECOVERY_AT);

        List<OrderOutboxBackfill.Outcome> firstRecovery = backfill.backfill(List.of(orderA, orderB));
        assertThat(firstRecovery).extracting(OrderOutboxBackfill.Outcome::orderId, OrderOutboxBackfill.Outcome::result).containsExactly(
            tuple(orderA, OrderOutboxBackfill.Result.ADDED),
            tuple(orderB, OrderOutboxBackfill.Result.ADDED)
        );

        // Outbox: 저장된 확정 사실 그대로(eventId·confirmedAt·품목), 복구 시각부터 relay 대상
        for (CaseFacts facts : List.of(factsA, factsB)) {
            OrderOutboxModel row = onlyOutboxRow(facts.orderId());
            assertThat(row.getEventId()).isEqualTo(facts.eventId());
            assertThat(row.getStatus()).isEqualTo(OrderOutboxStatus.PENDING);
            assertThat(row.getNextAttemptAt()).isEqualTo(RECOVERY_AT);
            JsonNode payload = MAPPER.readTree(row.getPayload());
            assertThat(payload.get("eventId").asText()).isEqualTo(facts.eventId());
            assertThat(payload.get("confirmedAt").asText()).as("복구 시각이 아니라 W6 확정 시각").isEqualTo(facts.confirmedAt());
            assertThat(itemsOf(payload)).isEqualTo(facts.items());
        }
        assertThat(onlyOutboxRow(orderB).getPayload()).as("B는 W6가 broker에 보낸 값과 같은 바이트").isEqualTo(w6RecordOfB);

        // relay와 기존 consumer를 실행한다
        assertThat(relay.runOnce()).isEqualTo(new OrderOutboxRelay.Run(true, 2, 2, 0));
        await().atMost(DEADLINE).untilAsserted(() -> assertThat(E2eSupport.committedOffset(GROUP, TOPIC)).isEqualTo(3L));

        // A: 빠졌던 구매 활동이 생긴다. B: 재발행이 와도 활동 행·수량이 늘지 않는다
        assertThat(E2eSupport.purchaseActivities(jdbcTemplate, orderA)).containsExactly(tuple(product.getId(), ORDER_QUANTITY));
        assertThat(E2eSupport.purchaseActivities(jdbcTemplate, orderB)).containsExactly(tuple(product.getId(), ORDER_QUANTITY));
        assertThat(E2eSupport.processedEventIds(jdbcTemplate, orderA)).containsExactly(factsA.eventId());
        assertThat(E2eSupport.processedEventIds(jdbcTemplate, orderB)).containsExactly(factsB.eventId());
        assertThat(output.getOut())
            .contains("result=RECORDED, eventId=" + factsA.eventId() + ", orderId=" + orderA)
            .contains("result=ALREADY_PROCESSED, eventId=" + factsB.eventId() + ", orderId=" + orderB);
        List<BrokerRecords.Record> afterRecovery = BrokerRecords.readAll(TOPIC);
        assertThat(afterRecovery).filteredOn(r -> r.key().equals(String.valueOf(orderA))).hasSize(1);
        assertThat(afterRecovery).filteredOn(r -> r.key().equals(String.valueOf(orderB)))
            .as("B: W6 record와 복구 재발행 record, 같은 eventId·같은 값").hasSize(2)
            .allSatisfy(r -> assertThat(r.value()).isEqualTo(w6RecordOfB));

        // 같은 대상의 두 번째 복구는 Outbox·구매 효과를 늘리지 않는다
        List<OrderOutboxBackfill.Outcome> secondRecovery = backfill.backfill(List.of(orderA, orderB));
        assertThat(secondRecovery).extracting(OrderOutboxBackfill.Outcome::result)
            .containsExactly(OrderOutboxBackfill.Result.ALREADY_PRESENT, OrderOutboxBackfill.Result.ALREADY_PRESENT);
        assertThat(relay.runOnce()).isEqualTo(new OrderOutboxRelay.Run(true, 0, 0, 0));
        assertThat(orderOutboxJpaRepository.count()).isEqualTo(2);
        assertThat(List.of(onlyOutboxRow(orderA), onlyOutboxRow(orderB)))
            .extracting(OrderOutboxModel::getStatus).containsOnly(OrderOutboxStatus.PUBLISHED);
        assertThat(BrokerRecords.readAll(TOPIC)).hasSize(3);
        assertThat(E2eSupport.purchaseActivities(jdbcTemplate, orderA)).containsExactly(tuple(product.getId(), ORDER_QUANTITY));
        assertThat(E2eSupport.purchaseActivities(jdbcTemplate, orderB)).containsExactly(tuple(product.getId(), ORDER_QUANTITY));

        // PG·주문·예약: 복구 중 PG 추가 호출 0, 외부 거래 그대로, 주문 재확정·예약 추가 소비 0, 직접 발행 0
        PgCalls recovered = pgCalls();
        assertThat(recovered).as("복구 중 PG create·조회·요청 증가 0").isEqualTo(prepared);
        for (CaseFacts facts : List.of(factsA, factsB)) {
            assertThat(pg.transactionsOf(facts.providerOrderId())).extracting(TestPgTransaction::transactionKey)
                .containsExactly(facts.transactionKey());
            assertThat(orderJpaRepository.findById(facts.orderId()).orElseThrow().getConfirmedAt())
                .as("주문을 다시 확정하지 않았다").isEqualTo(Instant.parse(facts.confirmedAt()));
            assertThat(stockReservationJpaRepository.findByOrderId(facts.orderId()).orElseThrow().getStatus()).isEqualTo(ReservationStatus.CONSUMED);
        }
        assertThat(stockOf(product)).as("예약 추가 소비·반환 없음").isEqualTo(stockBeforeRecovery).isEqualTo(INITIAL_STOCK - 2 * ORDER_QUANTITY);
        assertThat(pointJpaRepository.findByUserId(USER_ID).orElseThrow().getBalance()).isEqualTo(INITIAL_BALANCE);
        assertThat(w6Publisher.attempts()).as("복구 중 직접 발행 없음").hasSize(w6PublishAttempts);

        log.info("[W6 복구] 대상 A={}, B={} | 1차 {} | 2차 {} | broker record {}개 | PG 준비 {} → 복구 뒤 {} | 구매 활동 A={}, B={}",
            orderA, orderB, firstRecovery, secondRecovery, afterRecovery.size(), prepared, recovered,
            E2eSupport.purchaseActivities(jdbcTemplate, orderA), E2eSupport.purchaseActivities(jdbcTemplate, orderB));
    }

    private TestPgTransaction confirmThroughCallback(Long orderId) throws Exception {
        E2eSupport.confirm(mvc, USER_ID, orderId).andExpect(status().isAccepted());
        PaymentTestSupport.completeAt(pg, orderId, TestPgStatus.SUCCESS);
        TestPgTransaction transaction = PaymentTestSupport.onlyTransactionOf(pg, orderId);
        E2eSupport.callbackOf(mvc, transaction).andExpect(status().isOk()).andExpect(jsonPath("$.data.orderStatus").value("CONFIRMED"));
        return transaction;
    }

    /** W6 경로가 확정 뒤 보내려던 이벤트(발행기에 기록됨)와 DB의 확정 시각·PG 거래에서 이번 실행의 사실을 기록한다. */
    private CaseFacts factsOf(Long orderId, TestPgTransaction transaction) {
        OrderConfirmedEvent event = w6Publisher.attempts().stream()
            .filter(e -> e.orderId().equals(orderId))
            .findFirst()
            .orElseThrow(() -> new AssertionError("W6 경로가 이 주문의 발행을 시도하지 않았다: " + orderId));
        Instant confirmedAt = orderJpaRepository.findById(orderId).orElseThrow().getConfirmedAt();
        assertThat(confirmedAt).isEqualTo(Instant.parse(W6_CONFIRMED_AT));
        assertThat(event.confirmedAt()).isEqualTo(confirmedAt.toString());
        return new CaseFacts(orderId, event.eventId(), event.confirmedAt(), event.items(),
            ProviderOrderId.from(orderId), transaction.transactionKey());
    }

    private PgCalls pgCalls() {
        return new PgCalls(pg.createCount(), pg.transactionCount(), pg.lookupByKeyCount(), pg.lookupByOrderCount(), pg.requests().size());
    }

    private static List<OrderConfirmedEvent.Item> itemsOf(JsonNode payload) {
        return StreamSupport.stream(payload.get("items").spliterator(), false)
            .map(item -> new OrderConfirmedEvent.Item(item.get("productId").asLong(), item.get("quantity").asInt()))
            .toList();
    }

    private OrderOutboxModel onlyOutboxRow(Long orderId) {
        List<OrderOutboxModel> rows = orderOutboxJpaRepository.findAllByOrderId(orderId);
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    private int stockOf(ProductModel product) {
        return productJpaRepository.findById(product.getId()).orElseThrow().getStock();
    }
}
