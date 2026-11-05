package com.loopers.application.payment;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.order.event.OrderConfirmedEvent;
import com.loopers.application.order.event.OrderConfirmedMessageV1;
import com.loopers.application.order.event.OrderConfirmedOutboxWriter;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderRepository;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.payment.PaymentAttemptRepository;
import com.loopers.domain.payment.PaymentAttemptStatus;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.support.time.MutableClock;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

@SpringBootTest
class OrderConfirmedOutboxAtomicityTest {

    private static final Instant NOW = Instant.parse("2026-11-05T01:00:00.123456789Z");
    private static final Instant NOW_IN_MICROS = Instant.parse("2026-11-05T01:00:00.123456Z");
    private static final long USER_ID = 1L;
    private static final long PRICE = 3_500L;
    private static final int QUANTITY = 2;
    private static final long INITIAL_STOCK = 10L;

    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        MutableClock outboxClock() {
            return new MutableClock(NOW, ZoneOffset.UTC);
        }
    }

    @Autowired
    private PaymentRequestTransaction paymentRequestTransaction;
    @Autowired
    private PaymentResultService paymentResultService;
    @Autowired
    private MutableClock clock;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private PaymentAttemptRepository paymentAttemptRepository;
    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private BrandRepository brandRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @MockitoSpyBean
    private ObjectMapper objectMapper;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;
    @MockitoSpyBean
    private OrderConfirmedOutboxWriter outboxWriter;

    @BeforeEach
    void setUp() {
        clock.set(NOW);
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("최초 PG 성공은 확정 트랜잭션에 PENDING Outbox 한 행을 함께 저장한다.")
    @Test
    void storesPendingOutboxWithTheConfirmedOrder() throws Exception {
        Fixture fixture = fixture();

        PaymentReflection result = paymentResultService.applyLookup(fixture.attemptId(), success(fixture));

        assertThat(result.outcome()).isEqualTo(PaymentReflectionOutcome.APPLIED);
        Order confirmed = orderRepository.findById(fixture.orderId()).orElseThrow();
        assertThat(confirmed.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(confirmed.getConfirmedAt().toInstant()).isEqualTo(NOW_IN_MICROS);
        assertThat(paymentAttemptRepository.findById(fixture.attemptId()).orElseThrow().getStatus())
            .isEqualTo(PaymentAttemptStatus.SUCCEEDED);

        List<OutboxRow> rows = outboxRows(fixture.orderId());
        assertThat(rows).hasSize(1);
        OutboxRow row = rows.getFirst();
        assertThat(row.eventId()).isEqualTo("order-confirmed:v1:" + fixture.orderId());
        assertThat(row.orderId()).isEqualTo(fixture.orderId());
        assertThat(row.eventType()).isEqualTo("OrderConfirmed");
        assertThat(row.schemaVersion()).isEqualTo(1);
        assertThat(row.messageKey()).isEqualTo(String.valueOf(fixture.orderId()));
        assertThat(row.status()).isEqualTo("PENDING");
        assertThat(row.attemptCount()).isZero();
        assertThat(row.cycleAttemptCount()).isZero();
        assertThat(row.nextAttemptAt()).isNotNull();
        assertThat(row.publishedAt()).isNull();

        JsonNode payload = objectMapper.readTree(row.payload());
        assertThat(payload.path("eventType").asText()).isEqualTo("OrderConfirmed");
        assertThat(payload.path("schemaVersion").asInt()).isEqualTo(1);
        assertThat(payload.path("eventId").asText()).isEqualTo(row.eventId());
        assertThat(payload.path("orderId").asLong()).isEqualTo(fixture.orderId());
        assertThat(payload.path("confirmedAt").asText()).isEqualTo(NOW_IN_MICROS.toString());
        assertThat(payload.path("items").size()).isEqualTo(2);
        assertThat(payload.path("items").get(0).path("productId").asLong()).isEqualTo(fixture.firstProductId());
        assertThat(payload.path("items").get(0).path("quantity").asInt()).isEqualTo(QUANTITY);
        assertThat(payload.path("items").get(1).path("productId").asLong()).isEqualTo(fixture.secondProductId());
        assertThat(payload.path("items").get(1).path("quantity").asInt()).isEqualTo(1);
    }

    @DisplayName("같은 PG 성공을 다시 반영해도 최초 Outbox identity와 payload를 유지한다.")
    @Test
    void keepsTheFirstOutboxWhenSuccessIsRepeated() {
        Fixture fixture = fixture();
        paymentResultService.applyLookup(fixture.attemptId(), success(fixture));
        List<OutboxRow> first = outboxRows(fixture.orderId());
        clock.set(NOW.plusSeconds(3_600));

        PaymentReflection duplicate = paymentResultService.applyLookup(fixture.attemptId(), success(fixture));

        assertThat(duplicate.outcome()).isEqualTo(PaymentReflectionOutcome.UNCHANGED);
        assertThat(outboxRows(fixture.orderId())).containsExactlyElementsOf(first);
        assertThat(orderRepository.findById(fixture.orderId()).orElseThrow().getConfirmedAt().toInstant())
            .isEqualTo(NOW_IN_MICROS);
    }

    @DisplayName("성공이 아닌 PG 결과는 OrderConfirmed Outbox를 만들지 않는다.")
    @Test
    void doesNotCreateOutboxForNonSuccessResults() {
        Fixture pending = fixture();
        paymentResultService.applyLookup(pending.attemptId(), observation(pending, PgTransactionStatus.PENDING));
        assertThat(outboxRows(pending.orderId())).isEmpty();

        Fixture failed = fixture();
        paymentResultService.applyLookup(failed.attemptId(), observation(failed, PgTransactionStatus.FAILED));
        assertThat(outboxRows(failed.orderId())).isEmpty();

        Fixture unknown = fixture();
        paymentResultService.applyCreateResponse(unknown.attemptId(), new PaymentCreateObservation.Unknown("timeout"));
        assertThat(outboxRows(unknown.orderId())).isEmpty();
    }

    @DisplayName("Outbox payload 직렬화 실패는 주문 확정·결제 시도 종결도 rollback한다.")
    @Test
    void rollsBackConfirmationWhenOutboxPayloadSerializationFails() throws Exception {
        Fixture fixture = fixture();
        doThrow(JsonMappingException.from((JsonParser) null, "통제된 직렬화 오류"))
            .when(objectMapper).writeValueAsString(any(OrderConfirmedMessageV1.class));

        assertThatThrownBy(() -> paymentResultService.applyLookup(fixture.attemptId(), success(fixture)))
            .isInstanceOf(PaymentReflectionRolledBackException.class)
            .hasCauseInstanceOf(IllegalStateException.class);

        Order rolledBack = orderRepository.findById(fixture.orderId()).orElseThrow();
        assertThat(rolledBack.getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(rolledBack.getConfirmedAt()).isNull();
        assertThat(paymentAttemptRepository.findById(fixture.attemptId()).orElseThrow().getStatus())
            .isEqualTo(PaymentAttemptStatus.REQUESTED);
        assertThat(outboxRows(fixture.orderId())).isEmpty();
        assertThat(productRepository.findById(fixture.firstProductId()).orElseThrow().getStock().amount())
            .isEqualTo(INITIAL_STOCK - QUANTITY);
        assertThat(productRepository.findById(fixture.secondProductId()).orElseThrow().getStock().amount())
            .isEqualTo(INITIAL_STOCK - 1);
    }

    @DisplayName("Outbox 유일 제약 위반은 주문 확정·시도 종결·예약 소비도 rollback한다.")
    @Test
    void rollsBackConfirmationAndAttemptWhenOutboxInsertFails() {
        Fixture fixture = fixture();
        seedConflictingOutbox(fixture);

        assertThatThrownBy(() -> paymentResultService.applyLookup(fixture.attemptId(), success(fixture)))
            .isInstanceOf(PaymentReflectionRolledBackException.class);

        Order rolledBack = orderRepository.findById(fixture.orderId()).orElseThrow();
        assertThat(rolledBack.getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(rolledBack.getConfirmedAt()).isNull();
        assertThat(paymentAttemptRepository.findById(fixture.attemptId()).orElseThrow().getStatus())
            .isEqualTo(PaymentAttemptStatus.REQUESTED);
        assertThat(productRepository.findById(fixture.firstProductId()).orElseThrow().getStock().amount())
            .isEqualTo(INITIAL_STOCK - QUANTITY);
        assertThat(productRepository.findById(fixture.secondProductId()).orElseThrow().getStock().amount())
            .isEqualTo(INITIAL_STOCK - 1);
        assertThat(outboxRows(fixture.orderId())).singleElement().satisfies(row ->
            assertThat(row.payload()).isEqualTo("conflicting-payload"));
    }

    @DisplayName("Outbox insert 뒤 확정 TX commit에 실패하면 주문·시도·Outbox가 함께 rollback된다.")
    @Test
    void rollsBackOutboxWhenConfirmationCommitFails() {
        Fixture fixture = fixture();
        doAnswer(invocation -> {
            invocation.callRealMethod();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void beforeCommit(boolean readOnly) {
                    throw new DataAccessResourceFailureException("통제된 commit 직전 오류");
                }
            });
            return null;
        }).when(outboxWriter).record(any(OrderConfirmedEvent.class));

        assertThatThrownBy(() -> paymentResultService.applyLookup(fixture.attemptId(), success(fixture)))
            .isInstanceOf(PaymentReflectionRolledBackException.class)
            .hasCauseInstanceOf(DataAccessResourceFailureException.class);

        Order order = orderRepository.findById(fixture.orderId()).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(order.getConfirmedAt()).isNull();
        assertThat(paymentAttemptRepository.findById(fixture.attemptId()).orElseThrow().getStatus())
            .isEqualTo(PaymentAttemptStatus.REQUESTED);
        assertThat(outboxRows(fixture.orderId())).isEmpty();
        assertThat(productRepository.findById(fixture.firstProductId()).orElseThrow().getStock().amount())
            .isEqualTo(INITIAL_STOCK - QUANTITY);
        assertThat(productRepository.findById(fixture.secondProductId()).orElseThrow().getStock().amount())
            .isEqualTo(INITIAL_STOCK - 1);
    }

    @DisplayName("동시에 같은 PG 성공을 반영해도 확정 Outbox는 한 행만 남는다.")
    @Test
    void createsOneOutboxForConcurrentSuccess() throws Exception {
        Fixture fixture = fixture();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<PaymentReflection> first = executor.submit(() -> {
                start.await();
                return paymentResultService.applyLookup(fixture.attemptId(), success(fixture));
            });
            Future<PaymentReflection> second = executor.submit(() -> {
                start.await();
                return paymentResultService.applyLookup(fixture.attemptId(), success(fixture));
            });
            start.countDown();

            assertThat(List.of(first.get(10, TimeUnit.SECONDS).outcome(),
                second.get(10, TimeUnit.SECONDS).outcome()))
                .containsExactlyInAnyOrder(PaymentReflectionOutcome.APPLIED, PaymentReflectionOutcome.UNCHANGED);
            assertThat(outboxRows(fixture.orderId())).hasSize(1);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private Fixture fixture() {
        Brand brand = brandRepository.save(Brand.create("outbox-" + UUID.randomUUID()));
        Product first = product("첫 상품", brand.getId());
        Product second = product("두 번째 상품", brand.getId());

        // 품목을 상품 ID 역순으로 주문에 담아 payload 정렬 계약도 확인한다.
        Order order = orderRepository.save(Order.create(USER_ID, List.of(
            OrderItem.create(second.getId(), second.getName(), PRICE, 1),
            OrderItem.create(first.getId(), first.getName(), PRICE, QUANTITY)
        )));
        PaymentReservation reservation = paymentRequestTransaction.reserve(
            USER_ID, order.getId(), UUID.randomUUID().toString());
        PaymentReservation.Started started = (PaymentReservation.Started) reservation;
        return new Fixture(started.attemptId(), order.getId(), started.providerOrderId(),
            first.getId(), second.getId(), started.amount());
    }

    private Product product(String name, Long brandId) {
        Product product = Product.create(brandId, name, PRICE);
        product.changeStockTo(INITIAL_STOCK);
        return productRepository.save(product);
    }

    private PaymentLookupObservation success(Fixture fixture) {
        return observation(fixture, PgTransactionStatus.SUCCESS);
    }

    private PaymentLookupObservation observation(Fixture fixture, PgTransactionStatus status) {
        return new PaymentLookupObservation.Found(List.of(
            new PgTransaction("TX-" + fixture.attemptId(), fixture.providerOrderId(), fixture.amount(), status)));
    }

    private List<OutboxRow> outboxRows(Long orderId) {
        return jdbcTemplate.query("""
            SELECT event_id, order_id, event_type, schema_version, message_key, payload,
                   status, attempt_count, cycle_attempt_count, next_attempt_at, published_at
            FROM order_confirmed_outbox
            WHERE order_id = ?
            ORDER BY id
            """, (resultSet, rowNum) -> new OutboxRow(
            resultSet.getString("event_id"),
            resultSet.getLong("order_id"),
            resultSet.getString("event_type"),
            resultSet.getInt("schema_version"),
            resultSet.getString("message_key"),
            resultSet.getString("payload"),
            resultSet.getString("status"),
            resultSet.getInt("attempt_count"),
            resultSet.getInt("cycle_attempt_count"),
            resultSet.getTimestamp("next_attempt_at"),
            resultSet.getTimestamp("published_at")
        ), orderId);
    }

    private void seedConflictingOutbox(Fixture fixture) {
        Timestamp now = Timestamp.from(NOW_IN_MICROS);
        jdbcTemplate.update("""
            INSERT INTO order_confirmed_outbox
                (created_at, updated_at, event_id, order_id, event_type, schema_version,
                 message_key, payload, status, attempt_count, cycle_attempt_count, next_attempt_at)
            VALUES (?, ?, ?, ?, 'OrderConfirmed', 1, ?, 'conflicting-payload', 'PENDING', 0, 0, ?)
            """, now, now, "order-confirmed:v1:" + fixture.orderId(), fixture.orderId(),
            String.valueOf(fixture.orderId()), now);
    }

    private record Fixture(Long attemptId, Long orderId, String providerOrderId,
                           Long firstProductId, Long secondProductId, long amount) {}

    private record OutboxRow(String eventId, Long orderId, String eventType, int schemaVersion,
                             String messageKey, String payload, String status, int attemptCount,
                             int cycleAttemptCount, Timestamp nextAttemptAt, Timestamp publishedAt) {}
}
