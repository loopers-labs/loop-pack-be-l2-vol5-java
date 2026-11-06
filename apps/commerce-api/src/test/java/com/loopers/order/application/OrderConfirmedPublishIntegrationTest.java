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
import com.loopers.outbox.adapter.out.persistence.OrderOutboxJpaRepository;
import com.loopers.outbox.application.port.in.OrderOutboxRelayUseCase;
import com.loopers.outbox.application.port.in.OutboxAppendUseCase;
import com.loopers.outbox.application.port.in.OutboxAppendUseCase.OrderOutboxEntry;
import com.loopers.outbox.domain.OrderOutbox;
import com.loopers.outbox.domain.OrderOutboxStatus;
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
import com.loopers.payment.domain.PaymentStatus;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;

/**
 * 발행 경계 (W6 D-3·ADR-W6-02, W7 docs/week7/plan.md). 검증된 PG SUCCESS를 처음 CONFIRMED로 반영한 TX가 commit된 경우에만
 * order_outbox에 PENDING 행을 남긴다. 대기·실패·rollback·중복 적용은 행도 record도 만들지 않는다.
 * W7: commit만으로는 broker로 나가지 않는다(after-commit 직접 발행 제거). 보내는 것은 relay이고, 테스트가 relay를 직접 부른다.
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
    private OrderOutboxJpaRepository orderOutboxJpaRepository;

    @Autowired
    private OrderOutboxRelayUseCase relay;

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

    private List<OrderOutbox> outboxRows() {
        return orderOutboxJpaRepository.findAll().stream()
            .filter(row -> row.getOrderId().equals(info.orderId()))
            .toList();
    }

    private List<ConsumerRecord<String, String>> recordsOf(String eventId, Duration wait) {
        return KafkaTopicReader.read(KafkaTestContainersConfig.bootstrapServers(), orderEventsTopic,
            record -> record.value().contains("\"eventId\":\"" + eventId + "\""), 2, wait);
    }

    @DisplayName("검증된 PG SUCCESS를 처음 CONFIRMED로 반영하면, ")
    @Nested
    class FirstConfirmation {

        @DisplayName("C-1 같은 TX에서 Outbox 행 1건(주문 ID · OrderConfirmed · 스키마 1 · key=주문 ID · PENDING)을 남긴다.")
        @Test
        void recordsOneOutboxRowInTheConfirmingTransaction() {
            PG.complete(transactionKey, "SUCCESS", null);

            PaymentApplyOutcome outcome = paymentResultApplier.reconcile(info.paymentId());

            assertThat(outcome).isEqualTo(PaymentApplyOutcome.APPLIED);
            assertThat(order().getStatus()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(outboxRows()).singleElement().satisfies(row -> {
                assertThat(row.getOrderId()).isEqualTo(info.orderId());
                assertThat(row.getEventType()).isEqualTo("OrderConfirmed");
                assertThat(row.getSchemaVersion()).isEqualTo(1);
                assertThat(row.getMessageKey()).isEqualTo(String.valueOf(info.orderId()));
                assertThat(row.getEventId()).hasSize(36);
                assertThat(row.getStatus()).isEqualTo(OrderOutboxStatus.PENDING);
                assertThat(row.getAttemptCount()).isZero();
            });
        }

        @DisplayName("C-1 payload는 DB 사실과 같다 — eventId는 기록의 값, confirmedAt은 저장된 확정 시각, items는 상품 ID 오름차순 [(에어맥스, 2), (에어포스, 1)]")
        @Test
        void payloadMatchesStoredFacts() throws Exception {
            PG.complete(transactionKey, "SUCCESS", null);

            paymentResultApplier.reconcile(info.paymentId());

            OrderOutbox row = outboxRows().get(0);
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

        @DisplayName("C-1·C-4 commit만으로는 broker로 나가지 않는다. relay가 돌면 저장한 원문 그대로 1건 보내고, broker 응답의 partition·offset으로 PUBLISHED를 기록한다.")
        @Test
        void sendsTheStoredPayloadOnlyThroughRelayAndMarksPublished() {
            PG.complete(transactionKey, "SUCCESS", null);
            paymentResultApplier.reconcile(info.paymentId());
            OrderOutbox row = outboxRows().get(0);

            // commit 뒤 relay 전: after-commit 직접 발행이 없으므로 broker에 아무것도 없다
            assertThat(recordsOf(row.getEventId(), Duration.ofSeconds(3))).isEmpty();

            relay.relayOnce();

            List<ConsumerRecord<String, String>> records = recordsOf(row.getEventId(), Duration.ofSeconds(10));
            assertThat(records).singleElement().satisfies(record -> {
                assertThat(record.key()).isEqualTo(String.valueOf(info.orderId()));
                assertThat(record.value()).isEqualTo(row.getPayload());
            });
            OrderOutbox marked = orderOutboxJpaRepository.findByEventId(row.getEventId()).orElseThrow();
            assertThat(marked.getStatus()).isEqualTo(OrderOutboxStatus.PUBLISHED);
            assertThat(marked.getPublishedAt()).isNotNull();
            assertThat(marked.getPublishedPartition()).isEqualTo(records.get(0).partition());
            assertThat(marked.getPublishedOffset()).isEqualTo(records.get(0).offset());
            assertThat(marked.getLastError()).isNull();
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
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM order_outbox", Long.class)).isZero();
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
            relay.relayOnce();
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

    /**
     * W7 체크리스트 "UNKNOWN에서 거짓 이벤트나 추가 결제가 생기지 않는다". UNKNOWN은 PG에 보냈을 수 있으나 결과를 모르는 결제다 (W5 P-5).
     * 조회로 SUCCESS가 밝혀질 때만 확정·발행 기록이 생기고, 어느 경우에도 PG 생성 요청을 다시 보내지 않는다.
     */
    @DisplayName("결제 결과를 모르면(UNKNOWN), ")
    @Nested
    class Unknown {

        private PaymentInfo unknown;
        private int createCallsBefore;

        /**
         * 에어맥스 1개 주문(1,000원)을 PG에 생성 요청까지 보냈지만 응답을 못 받아 UNKNOWN으로 둔다 — 거래 키를 모른다.
         * PG에 거래가 실제로 생겼는지는 pgCreated로 정한다. 생겼으면 그 거래 키를 돌려준다.
         */
        private String arrangeUnknown(boolean pgCreated) {
            Long userId = order().getUserId();
            Long orderId = orderCommandUseCase.create(userId, List.of(new OrderLines.Line(airMax.getId(), 1))).id();
            unknown = paymentRequestUseCase.request(new PaymentRequestCommand(userId, orderId, "attempt-unknown", "SAMSUNG", "1234-5678-9814-1451"));
            Payment payment = paymentPort.findById(unknown.paymentId()).orElseThrow();
            String key = null;
            if (pgCreated) {
                key = paymentGatewayPort.request(new PgPaymentRequest(payment.getProviderOrderId(), "SAMSUNG", "1234-5678-9814-1451", 1_000L)).transactionKey();
            }
            transactionTemplate.executeWithoutResult(status -> {
                Payment locked = paymentPort.findByIdForUpdate(unknown.paymentId()).orElseThrow();
                locked.markSending(ZonedDateTime.now());
                paymentPort.save(locked);
            });
            paymentResultApplier.recordUnknown(unknown.paymentId());
            createCallsBefore = PG.createCalls();
            return key;
        }

        private OrderModel unknownOrder() {
            return orderJpaRepository.findById(unknown.orderId()).orElseThrow();
        }

        private List<OrderOutbox> unknownOutboxRows() {
            return orderOutboxJpaRepository.findAll().stream()
                .filter(row -> row.getOrderId().equals(unknown.orderId()))
                .toList();
        }

        @DisplayName("UNKNOWN이 된 것만으로는 주문이 PAYMENT_PENDING에 머물고 발행 기록이 없다.")
        @Test
        void unknownAloneRecordsNothing() {
            arrangeUnknown(true);

            assertThat(paymentPort.findById(unknown.paymentId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.UNKNOWN);
            assertThat(unknownOrder().getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
            assertThat(unknownOutboxRows()).isEmpty();
        }

        @DisplayName("PG 조회가 실패하면(500) NOT_FINAL로 미루고, 주문 PAYMENT_PENDING · 발행 기록 0건 · PG 생성 요청 0회 추가다.")
        @Test
        void lookupUnavailableRecordsNothingAndDoesNotCreateAgain() {
            arrangeUnknown(true);
            PG.failNextLookups(10);

            PaymentApplyOutcome outcome = paymentResultApplier.reconcile(unknown.paymentId());

            assertThat(outcome).isEqualTo(PaymentApplyOutcome.NOT_FINAL);
            assertThat(unknownOrder().getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
            assertThat(unknownOutboxRows()).isEmpty();
            assertThat(PG.createCalls()).isEqualTo(createCallsBefore);
        }

        @DisplayName("PG에 거래가 없으면(조회 0건) 실패로 확정하지도 발행하지도 않는다 — 결제 UNKNOWN · 주문 PAYMENT_PENDING · 발행 기록 0건 · PG 생성 요청 0회 추가.")
        @Test
        void notFoundRecordsNothingAndDoesNotCreateAgain() {
            arrangeUnknown(false);

            PaymentApplyOutcome outcome = paymentResultApplier.reconcile(unknown.paymentId());

            assertThat(outcome).isEqualTo(PaymentApplyOutcome.NOT_FINAL);
            assertThat(paymentPort.findById(unknown.paymentId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.UNKNOWN);
            assertThat(unknownOrder().getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
            assertThat(unknownOutboxRows()).isEmpty();
            assertThat(PG.createCalls()).isEqualTo(createCallsBefore);
        }

        @DisplayName("조회로 SUCCESS가 밝혀지면 그때 처음 확정하고 발행 기록 1건을 남긴다. 한 번 더 조회해도 1건이고, PG 생성 요청은 0회 추가다.")
        @Test
        void successFoundLaterRecordsExactlyOnceWithoutCreatingAgain() {
            String key = arrangeUnknown(true);
            PG.complete(key, "SUCCESS", null);

            PaymentApplyOutcome first = paymentResultApplier.reconcile(unknown.paymentId());
            PaymentApplyOutcome second = paymentResultApplier.reconcile(unknown.paymentId());

            assertThat(first).isEqualTo(PaymentApplyOutcome.APPLIED);
            assertThat(second).isEqualTo(PaymentApplyOutcome.ALREADY_APPLIED);
            assertThat(unknownOrder().getStatus()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(unknownOutboxRows()).hasSize(1);
            assertThat(PG.createCalls()).isEqualTo(createCallsBefore);
        }
    }

    @DisplayName("ADR-W6-02 발행 기록은 트랜잭션 밖에서 남길 수 없다 — 확정과 함께 commit돼야 의미가 있다.")
    @Test
    void appendOutsideTransactionIsRejected() {
        OrderOutboxEntry entry = new OrderOutboxEntry("evt-outside", 1L, "OrderConfirmed", 1, "{}");

        assertThatThrownBy(() -> outboxAppendUseCase.append(entry)).isInstanceOf(IllegalTransactionStateException.class);
        assertThat(orderOutboxJpaRepository.findByEventId("evt-outside")).isEmpty();
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        Iterator<String> it = node.fieldNames();
        it.forEachRemaining(names::add);
        return names;
    }
}
