package com.loopers.order.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.brand.adapter.out.persistence.BrandJpaRepository;
import com.loopers.brand.domain.BrandModel;
import com.loopers.order.adapter.out.persistence.OrderJpaRepository;
import com.loopers.order.application.port.in.OrderCommandUseCase;
import com.loopers.order.domain.OrderLines;
import com.loopers.order.domain.OrderModel;
import com.loopers.order.domain.OrderStatus;
import com.loopers.outbox.adapter.out.persistence.EventOutboxJpaRepository;
import com.loopers.outbox.application.port.in.OutboxAppendUseCase;
import com.loopers.outbox.domain.EventOutbox;
import com.loopers.outbox.domain.OutboxMessage;
import com.loopers.payment.application.PaymentResultApplier;
import com.loopers.payment.application.port.in.PaymentDispatchUseCase;
import com.loopers.payment.application.port.in.PaymentInfo;
import com.loopers.payment.application.port.in.PaymentRequestCommand;
import com.loopers.payment.application.port.in.PaymentRequestUseCase;
import com.loopers.payment.application.port.out.PaymentGatewayPort;
import com.loopers.payment.application.port.out.PaymentPort;
import com.loopers.payment.application.port.out.PgPaymentRequest;
import com.loopers.payment.application.port.out.PgTransaction;
import com.loopers.payment.domain.Payment;
import com.loopers.payment.domain.PaymentApplyOutcome;
import com.loopers.product.adapter.out.persistence.ProductJpaRepository;
import com.loopers.product.application.port.in.StockReservationUseCase;
import com.loopers.product.domain.ProductModel;
import com.loopers.support.pg.LocalTestPgServer;
import com.loopers.testcontainers.KafkaTestContainersConfig;
import com.loopers.user.adapter.out.persistence.UserJpaRepository;
import com.loopers.user.domain.UserModel;
import com.loopers.utils.DatabaseCleanUp;
import com.loopers.utils.KafkaTopicReader;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;

/**
 * W6 발행 경계 (D-3·D-4, ADR-W6-02·03). 검증된 PG SUCCESS를 처음 CONFIRMED로 반영한 TX가 commit된 경우에만
 * 발행 기록을 남기고 실제 broker로 보낸다. 대기·실패·rollback·중복 적용은 기록도 record도 만들지 않는다.
 * <p>
 * 공유 클러스터의 order-events에는 다른 테스트의 record도 쌓인다. 테이블 truncate로 주문 ID가 다시 1부터 시작하므로
 * record는 key가 아니라 이 테스트가 만든 eventId로 찾는다.
 */
@SpringBootTest(properties = "pg.local.lookup-read-timeout=300ms")
class OrderConfirmedPublishIntegrationTest {

    private static final LocalTestPgServer PG = LocalTestPgServer.start();

    @DynamicPropertySource
    static void pgProperties(DynamicPropertyRegistry registry) {
        registry.add("pg.local.base-url", PG::baseUrl);
    }

    @AfterAll
    static void stopPg() {
        PG.stop();
    }

    @Autowired
    private PaymentResultApplier paymentResultApplier;

    @Autowired
    private PaymentRequestUseCase paymentRequestUseCase;

    @MockitoBean
    private PaymentDispatchUseCase paymentDispatchUseCase;

    @MockitoSpyBean
    private StockReservationUseCase stockReservationUseCase;

    @Autowired
    private OutboxAppendUseCase outboxAppendUseCase;

    @Autowired
    private PaymentGatewayPort paymentGatewayPort;

    @Autowired
    private PaymentPort paymentPort;

    @Autowired
    private OrderCommandUseCase orderCommandUseCase;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private UserJpaRepository userJpaRepository;

    @Autowired
    private BrandJpaRepository brandJpaRepository;

    @Autowired
    private ProductJpaRepository productJpaRepository;

    @Autowired
    private OrderJpaRepository orderJpaRepository;

    @Autowired
    private EventOutboxJpaRepository eventOutboxJpaRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @Value("${kafka.topics.order-events}")
    private String orderEventsTopic;

    private ProductModel airMax;
    private ProductModel airForce;
    private PaymentInfo info;
    private String transactionKey;

    @BeforeEach
    void setUp() {
        PG.reset();
        circuitBreakerRegistry.circuitBreaker("pgLookup").reset();
        UserModel user = userJpaRepository.save(new UserModel("고객"));
        BrandModel nike = brandJpaRepository.save(new BrandModel("나이키", null));
        airMax = productJpaRepository.save(new ProductModel(nike.getId(), "에어맥스", 1_000, 5));
        airForce = productJpaRepository.save(new ProductModel(nike.getId(), "에어포스", 2_000, 5));
        info = pendingPayment(user);
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    /** 에어포스 1개 → 에어맥스 2개 순서로 담은 주문(4,000원)을 선점하고, PG에 거래를 만든 뒤 결제를 PENDING으로 맞춘다. */
    private PaymentInfo pendingPayment(UserModel user) {
        Long orderId = orderCommandUseCase.create(user.getId(), List.of(
            new OrderLines.Line(airForce.getId(), 1),
            new OrderLines.Line(airMax.getId(), 2)
        )).id();
        PaymentInfo requested = paymentRequestUseCase.request(new PaymentRequestCommand(user.getId(), orderId, "attempt-1", "SAMSUNG", "1234-5678-9814-1451"));
        Payment payment = paymentPort.findById(requested.paymentId()).orElseThrow();
        PgTransaction created = paymentGatewayPort.request(new PgPaymentRequest(payment.getProviderOrderId(), "SAMSUNG", "1234-5678-9814-1451", 4_000L));
        transactionKey = created.transactionKey();
        transactionTemplate.executeWithoutResult(status -> {
            Payment locked = paymentPort.findByIdForUpdate(requested.paymentId()).orElseThrow();
            locked.markSending(ZonedDateTime.now());
            locked.recordAccepted(transactionKey, ZonedDateTime.now());
            paymentPort.save(locked);
        });
        return requested;
    }

    private OrderModel order() {
        return orderJpaRepository.findById(info.orderId()).orElseThrow();
    }

    private List<EventOutbox> outboxRows() {
        return eventOutboxJpaRepository.findAll().stream()
            .filter(row -> row.getAggregateId().equals(info.orderId()))
            .toList();
    }

    private List<ConsumerRecord<String, String>> recordsOf(String eventId, Duration wait) {
        return KafkaTopicReader.read(KafkaTestContainersConfig.bootstrapServers(), orderEventsTopic,
            record -> record.value().contains("\"eventId\":\"" + eventId + "\""), 2, wait);
    }

    @DisplayName("검증된 PG SUCCESS를 처음 CONFIRMED로 반영하면, ")
    @Nested
    class FirstConfirmation {

        @DisplayName("C-1 같은 TX에서 발행 기록 1건(ORDER · 주문 ID · OrderConfirmed · order-events · key=주문 ID)을 남긴다.")
        @Test
        void recordsOneOutboxRowInTheConfirmingTransaction() {
            PG.complete(transactionKey, "SUCCESS", null);

            PaymentApplyOutcome outcome = paymentResultApplier.reconcile(info.paymentId());

            assertThat(outcome).isEqualTo(PaymentApplyOutcome.APPLIED);
            assertThat(order().getStatus()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(outboxRows()).singleElement().satisfies(row -> {
                assertThat(row.getAggregateType()).isEqualTo("ORDER");
                assertThat(row.getEventType()).isEqualTo("OrderConfirmed");
                assertThat(row.getTopic()).isEqualTo(orderEventsTopic);
                assertThat(row.getMessageKey()).isEqualTo(String.valueOf(info.orderId()));
                assertThat(row.getEventId()).hasSize(36);
            });
        }

        @DisplayName("C-1 payload는 DB 사실과 같다 — eventId는 기록의 값, confirmedAt은 저장된 확정 시각, items는 상품 ID 오름차순 [(에어맥스, 2), (에어포스, 1)]")
        @Test
        void payloadMatchesStoredFacts() throws Exception {
            PG.complete(transactionKey, "SUCCESS", null);

            paymentResultApplier.reconcile(info.paymentId());

            EventOutbox row = outboxRows().get(0);
            JsonNode payload = objectMapper.readTree(row.getPayload());
            assertThat(payload.get("eventId").asText()).isEqualTo(row.getEventId());
            assertThat(payload.get("eventType").asText()).isEqualTo("OrderConfirmed");
            assertThat(payload.get("schemaVersion").asInt()).isEqualTo(1);
            assertThat(payload.get("orderId").asLong()).isEqualTo(info.orderId());
            // 같은 순간이고, 표기는 KST(+09:00)로 고정한다 — 다시 읽은 엔티티는 UTC(Z)로 나온다.
            assertThat(OffsetDateTime.parse(payload.get("confirmedAt").asText()).toInstant()).isEqualTo(order().getConfirmedAt().toInstant());
            assertThat(payload.get("confirmedAt").asText()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{6}\\+09:00");
            assertThat(payload.get("items")).hasSize(2);
            assertThat(payload.get("items").get(0).get("productId").asLong()).isEqualTo(airMax.getId());
            assertThat(payload.get("items").get(0).get("quantity").asInt()).isEqualTo(2);
            assertThat(payload.get("items").get(1).get("productId").asLong()).isEqualTo(airForce.getId());
            assertThat(payload.get("items").get(1).get("quantity").asInt()).isEqualTo(1);
        }

        @DisplayName("C-1·C-3 payload의 필드는 streamer와 함께 쓰는 견본(order-confirmed-v1.json)과 같다 — 이름·종류가 같고 그 밖의 필드(카드·가격·사용자)가 없다.")
        @Test
        void payloadHasTheSameShapeAsTheSharedFixture() throws Exception {
            PG.complete(transactionKey, "SUCCESS", null);

            paymentResultApplier.reconcile(info.paymentId());

            JsonNode payload = objectMapper.readTree(outboxRows().get(0).getPayload());
            JsonNode fixture = objectMapper.readTree(KafkaTopicReader.orderConfirmedFixture());
            assertThat(fieldNames(payload)).containsExactlyElementsOf(fieldNames(fixture));
            assertThat(fieldNames(payload.get("items").get(0))).containsExactlyElementsOf(fieldNames(fixture.get("items").get(0)));
            fixture.fieldNames().forEachRemaining(name -> assertThat(payload.get(name).getNodeType()).isEqualTo(fixture.get(name).getNodeType()));
        }

        @DisplayName("C-1 commit 뒤 실제 broker에 저장한 원문 그대로 1건 보내고, send 콜백 성공으로 기록에 partition·offset을 표시한다.")
        @Test
        void sendsTheStoredPayloadAndMarksPublished() {
            PG.complete(transactionKey, "SUCCESS", null);

            paymentResultApplier.reconcile(info.paymentId());

            EventOutbox row = outboxRows().get(0);
            List<ConsumerRecord<String, String>> records = recordsOf(row.getEventId(), Duration.ofSeconds(10));
            assertThat(records).singleElement().satisfies(record -> {
                assertThat(record.key()).isEqualTo(String.valueOf(info.orderId()));
                assertThat(record.value()).isEqualTo(row.getPayload());
            });
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                EventOutbox marked = eventOutboxJpaRepository.findByEventId(row.getEventId()).orElseThrow();
                assertThat(marked.getPublishedAt()).isNotNull();
                assertThat(marked.getPublishedPartition()).isEqualTo(records.get(0).partition());
                assertThat(marked.getPublishedOffset()).isEqualTo(records.get(0).offset());
                assertThat(marked.getLastError()).isNull();
            });
        }
    }

    @DisplayName("확정이 아니거나 이미 확정됐으면, ")
    @Nested
    class NoEvent {

        @DisplayName("C-2 PG가 아직 PENDING이면 주문은 PAYMENT_PENDING이고 발행 기록·record가 없다.")
        @Test
        void pendingPublishesNothing() {
            paymentResultApplier.reconcile(info.paymentId());

            assertThat(order().getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
            assertThat(outboxRows()).isEmpty();
        }

        @DisplayName("C-2 PG가 FAILED면 주문은 PAYMENT_FAILED이고 발행 기록·record가 없다.")
        @Test
        void failurePublishesNothing() {
            PG.complete(transactionKey, "FAILED", "한도 초과");

            paymentResultApplier.reconcile(info.paymentId());

            assertThat(order().getStatus()).isEqualTo(OrderStatus.PAYMENT_FAILED);
            assertThat(outboxRows()).isEmpty();
        }

        @DisplayName("C-2 확정 TX가 rollback되면(예약 소비 실패) 주문은 PAYMENT_PENDING이고 발행 기록·record가 없다 — 거짓 이벤트 없음.")
        @Test
        void rollbackPublishesNothing() {
            PG.complete(transactionKey, "SUCCESS", null);
            // 트랜잭션 프록시(MANDATORY)를 지나지 않도록 안쪽 spy에 stub을 건다 (W5 PaymentResultApplierIntegrationTest와 같은 방식)
            StockReservationUseCase spy = AopTestUtils.getUltimateTargetObject(stockReservationUseCase);
            doThrow(new IllegalStateException("예약 소비 실패 주입")).when(spy).consume(anyLong());

            assertThatThrownBy(() -> paymentResultApplier.reconcile(info.paymentId())).isInstanceOf(IllegalStateException.class);

            assertThat(order().getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
            assertThat(outboxRows()).isEmpty();
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM event_outbox", Long.class)).isZero();
        }

        @DisplayName("C-2 같은 PG 성공을 다시 적용해도(reconcile 두 번) 결제 단계(ALREADY_APPLIED)에서 막혀 발행 기록은 1건, broker record도 1건이고 PG 생성 호출은 늘지 않는다.")
        @Test
        void repeatedApplyPublishesOnce() {
            PG.complete(transactionKey, "SUCCESS", null);
            paymentResultApplier.reconcile(info.paymentId());
            int createCallsAfterFirst = PG.createCalls();

            PaymentApplyOutcome second = paymentResultApplier.reconcile(info.paymentId());

            assertThat(second).isEqualTo(PaymentApplyOutcome.ALREADY_APPLIED);
            assertThat(outboxRows()).hasSize(1);
            assertThat(recordsOf(outboxRows().get(0).getEventId(), Duration.ofSeconds(5))).hasSize(1);
            assertThat(PG.createCalls()).isEqualTo(createCallsAfterFirst);
        }

        @DisplayName("C-2 결제가 처음 종결되더라도 주문이 이미 CONFIRMED면 주문을 바꾸지 않고 발행 기록도 만들지 않는다 — 최초 확정에서만 기록한다.")
        @Test
        void alreadyConfirmedOrderDoesNotPublishAgain() {
            // arrange: 결제는 PENDING인데 주문만 먼저 CONFIRMED (어떤 경로로든 확정이 두 번 오는 상황을 직접 만든다)
            jdbcTemplate.update("UPDATE orders SET status = 'CONFIRMED', confirmed_at = NOW(6) WHERE id = ?", info.orderId());
            PG.complete(transactionKey, "SUCCESS", null);

            PaymentApplyOutcome outcome = paymentResultApplier.reconcile(info.paymentId());

            assertThat(outcome).isEqualTo(PaymentApplyOutcome.APPLIED);
            assertThat(outboxRows()).isEmpty();
        }
    }

    @DisplayName("ADR-W6-02 발행 기록은 트랜잭션 밖에서 남길 수 없다 — 확정과 함께 commit돼야 의미가 있다.")
    @Test
    void appendOutsideTransactionIsRejected() {
        OutboxMessage message = new OutboxMessage("evt-outside", "ORDER", 1L, "OrderConfirmed", orderEventsTopic, "1", "{}");

        assertThatThrownBy(() -> outboxAppendUseCase.append(message)).isInstanceOf(IllegalTransactionStateException.class);
        assertThat(eventOutboxJpaRepository.findByEventId("evt-outside")).isEmpty();
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        Iterator<String> it = node.fieldNames();
        it.forEachRemaining(names::add);
        return names;
    }
}
