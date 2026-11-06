package com.loopers.application.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.outbox.OutboxDeliveryTransactions;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.event.DomainEventPublisher;
import com.loopers.domain.order.OrderQuantities;
import com.loopers.domain.outbox.OutboxMessage;
import com.loopers.domain.outbox.OutboxRepository;
import com.loopers.domain.payment.PaymentRequestFingerprinter;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.infrastructure.payment.HttpTestPgClient;
import com.loopers.infrastructure.payment.PgHttpTimeouts;
import com.loopers.infrastructure.payment.support.LocalTestPg;
import com.loopers.infrastructure.user.FixtureUserInitializer;
import com.loopers.support.event.W6DirectPublicationTestConfiguration;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

/** Owned MySQL transactions; W6 preparation uses a real local PG but no broker (covered by the full E2E). */
@SpringBootTest(properties = {"commerce.outbox-relay.enabled=false", "commerce.order-events.enabled=false"})
@Import(W6DirectPublicationTestConfiguration.class)
@Timeout(30)
class OrderOutboxBackfillIntegrationTest {
    private static final Instant CONFIRMED_AT = Instant.parse("2026-10-29T00:01:02.123456Z");
    private static final Instant RECOVERY_AT = Instant.parse("2026-11-04T00:01:02.123456Z");
    @TestBean(methodName = "com.loopers.infrastructure.payment.support.PaymentFingerprintTestConfiguration#create")
    private PaymentRequestFingerprinter fingerprinter;
    @TestBean(name = "orderPaymentClock", methodName = "orderClock") private Clock orderPaymentClock;
    @TestBean(name = "outboxClock", methodName = "recoveryClock") private Clock outboxClock;
    @MockitoBean private DomainEventPublisher publisher;
    @MockitoSpyBean private OutboxRepository outbox;
    @Autowired private OrderOutboxBackfillService backfill;
    @Autowired private OutboxDeliveryTransactions delivery;
    @Autowired private OrderService orders;
    @Autowired private OrderPaymentStockService payments;
    @Autowired private BrandRepository brands;
    @Autowired private ProductRepository products;
    @Autowired private FixtureUserInitializer initializer;
    @Autowired private DatabaseCleanUp cleanup;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;
    @Autowired private ObjectMapper mapper;
    private LocalTestPg pg;
    private HttpTestPgClient client;

    static Clock orderClock() { return Clock.fixed(CONFIRMED_AT, ZoneOffset.UTC); }
    static Clock recoveryClock() { return Clock.fixed(RECOVERY_AT, ZoneOffset.UTC); }

    @BeforeEach
    void prepare() throws Exception {
        initializer.initialize();
        pg = new LocalTestPg();
        client = new HttpTestPgClient(pg.baseUri(), pg.callbackUri(), mapper, PgHttpTimeouts.defaults());
    }

    @AfterEach
    void cleanOwnedResources() {
        try {
            if (client != null) { client.close(); }
        } finally {
            try {
                if (pg != null) { pg.close(); }
            } finally {
                try {
                    OutboxRepository target = AopTestUtils.getUltimateTargetObject(outbox);
                    reset(target);
                } finally {
                    cleanup.truncateAllTables();
                }
            }
        }
    }

    @Test
    void missingRowIsInsertedAndPendingOrPublishedRepeatsPreserveEveryDeliveryField() throws Exception {
        long orderId = confirmedThroughPg();
        var business = businessSnapshot();
        var requests = pg.requests();
        var created = backfill.backfill(orderId);
        assertThat(created.outcome()).isEqualTo(OrderOutboxBackfillService.Outcome.CREATED);
        assertThat(created.eventId()).isEqualTo("order-confirmed:v1:" + orderId);
        var json = mapper.readTree(outbox.findByEventId(created.eventId()).orElseThrow().getPayload());
        assertThat(Instant.parse(json.path("confirmedAt").asText())).isEqualTo(CONFIRMED_AT);
        assertThat(outbox.findByEventId(created.eventId()).orElseThrow().getCreatedAt()).isEqualTo(RECOVERY_AT);

        var attempt = delivery.beginAttempt(created.outboxId(), RECOVERY_AT, Duration.ofSeconds(12)).orElseThrow();
        delivery.markFailed(created.outboxId(), attempt.attemptCount(), RECOVERY_AT, "SEND_FAILED");
        var pending = rows();
        assertThat(backfill.backfill(orderId).outcome()).isEqualTo(OrderOutboxBackfillService.Outcome.ALREADY_PRESENT);
        assertThat(rows()).isEqualTo(pending);

        delivery.markPublished(created.outboxId(), attempt.attemptCount(), RECOVERY_AT.plusSeconds(1));
        var published = rows();
        assertThat(backfill.backfill(orderId).outcome()).isEqualTo(OrderOutboxBackfillService.Outcome.ALREADY_PRESENT);
        assertThat(rows()).isEqualTo(published);
        assertThat(businessSnapshot()).usingRecursiveComparison().isEqualTo(business);
        assertThat(pg.requests()).isEqualTo(requests);
        assertThat(pg.createCalls()).isEqualTo(1);
    }

    @Test
    void anExceptionAfterTheRealInsertRollsBackOnlyTheNewOutboxAndCanBeRetried() {
        long orderId = confirmedThroughPg();
        var before = businessSnapshot();
        var requests = pg.requests();
        AtomicBoolean flushed = new AtomicBoolean();
        var injected = new IllegalStateException("test failure after actual backfill INSERT");
        OutboxRepository target = AopTestUtils.getUltimateTargetObject(outbox);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            entityManager.flush();
            assertThat(rows()).hasSize(1);
            flushed.set(true);
            throw injected;
        }).when(target).save(any(OutboxMessage.class));

        Throwable failure = catchThrowable(() -> backfill.backfill(orderId));
        assertThat(failure).isNotNull();
        while (failure.getCause() != null) { failure = failure.getCause(); }
        assertThat(failure).isSameAs(injected);
        assertThat(flushed).isTrue();
        assertThat(rows()).isEmpty(); // New connection after the proxied transaction rolled back.
        assertThat(businessSnapshot()).usingRecursiveComparison().isEqualTo(before);
        assertThat(pg.requests()).isEqualTo(requests);
        reset(target);
        assertThat(backfill.backfill(orderId).outcome()).isEqualTo(OrderOutboxBackfillService.Outcome.CREATED);
        assertThat(rows()).hasSize(1);
        assertThat(businessSnapshot()).usingRecursiveComparison().isEqualTo(before);
        assertThat(pg.requests()).isEqualTo(requests);
    }

    @Test
    void concurrentRecoverySerializesOnTheSameOrderAndCreatesOnlyOneRow() throws Exception {
        long orderId = confirmedThroughPg();
        var before = businessSnapshot();
        var requests = pg.requests();
        var workers = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var task = (java.util.concurrent.Callable<OrderOutboxBackfillService.Outcome>) () -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) { throw new IllegalStateException("Concurrent start timed out"); }
                return backfill.backfill(orderId).outcome();
            };
            var first = workers.submit(task);
            var second = workers.submit(task);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder(OrderOutboxBackfillService.Outcome.CREATED, OrderOutboxBackfillService.Outcome.ALREADY_PRESENT);
        } finally {
            start.countDown();
            workers.shutdownNow();
            assertThat(workers.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(rows()).hasSize(1);
        assertThat(businessSnapshot()).usingRecursiveComparison().isEqualTo(before);
        assertThat(pg.requests()).isEqualTo(requests);
    }

    @Test
    void laterProductChangesAndDeletionDoNotChangeThePastOrderFact() throws Exception {
        long orderId = confirmedThroughPg();
        // Test fixture change after a real W6 confirmation; the recovery must not read current product policy.
        jdbc.update("UPDATE product SET name='changed', price=999, deleted_at=CURRENT_TIMESTAMP");
        var before = businessSnapshot();
        var requests = pg.requests();
        var result = backfill.backfill(orderId);
        var json = mapper.readTree(outbox.findByEventId(result.eventId()).orElseThrow().getPayload());
        assertThat(json.path("items")).hasSize(1);
        assertThat(json.path("items").get(0).path("quantity").asInt()).isEqualTo(2);
        assertThat(Instant.parse(json.path("confirmedAt").asText())).isEqualTo(CONFIRMED_AT);
        assertThat(businessSnapshot()).usingRecursiveComparison().isEqualTo(before);
        assertThat(pg.requests()).isEqualTo(requests);
    }

    @Test
    void conflictingExistingPayloadIsRejectedWithoutRewritingTheRowOrBusiness() {
        long orderId = confirmedThroughPg();
        var first = backfill.backfill(orderId);
        // A corrupted existing fact is a rejection case, never a W6 preparation mechanism.
        jdbc.update("UPDATE outbox_message SET payload=JSON_SET(payload, '$.items[0].quantity', 3) WHERE id=?", first.outboxId());
        var rows = rows();
        var business = businessSnapshot();
        var requests = pg.requests();
        assertThat(catchThrowable(() -> backfill.backfill(orderId))).isInstanceOf(IllegalStateException.class);
        assertThat(rows()).isEqualTo(rows);
        assertThat(businessSnapshot()).usingRecursiveComparison().isEqualTo(business);
        assertThat(pg.requests()).isEqualTo(requests);
    }

    private long confirmedThroughPg() {
        Product product = products.save(new Product(brands.save(new Brand("Backfill brand")), "original", 100L, 5));
        long id = orders.create("alice", List.of(new OrderQuantities.Item(product.getId(), 2))).orderId();
        pg.nextCreateStatus("SUCCESS");
        new OrderPaymentCoordinator(payments, client).startNew("alice", id, UUID.randomUUID(), "LP-ORD-%010d".formatted(id),
            "TEST", "0000-0000-0000-0000");
        assertThat(jdbc.queryForObject("SELECT status FROM `order` WHERE id=?", String.class, id)).isEqualTo("CONFIRMED");
        assertThat(rows()).isEmpty();
        return id;
    }

    private List<Map<String, Object>> rows() { return jdbc.queryForList("SELECT * FROM outbox_message ORDER BY id"); }

    private Map<String, Object> businessSnapshot() {
        return Map.of("orders", jdbc.queryForList("SELECT * FROM `order` ORDER BY id"),
            "attempts", jdbc.queryForList("SELECT * FROM payment_attempt ORDER BY id"),
            "items", jdbc.queryForList("SELECT * FROM order_item ORDER BY id"),
            "products", jdbc.queryForList("SELECT * FROM product ORDER BY id"),
            "users", jdbc.queryForList("SELECT * FROM user ORDER BY id"),
            "revision", jdbc.queryForList("SELECT * FROM product_list_revision ORDER BY id"));
    }
}
