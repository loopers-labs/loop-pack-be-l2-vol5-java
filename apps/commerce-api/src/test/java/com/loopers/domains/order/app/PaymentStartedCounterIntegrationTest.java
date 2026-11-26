package com.loopers.domains.order.app;

import com.loopers.common.error.RuleViolationException;
import com.loopers.domains.brand.app.BrandApplicationService;
import com.loopers.domains.order.app.OrderTransactionTest.FailingPaymentRecordRepository;
import com.loopers.domains.payment.out.TestPgServer.CreateBehavior;
import com.loopers.domains.payment.out.TestPgServer;
import com.loopers.domains.product.app.ProductApplicationService;
import com.loopers.domains.user.out.UserJpaEntity;
import com.loopers.domains.user.out.UserJpaRepository;
import com.loopers.support.pg.MutablePaymentClock;
import com.loopers.support.pg.TestPgFixture;
import com.loopers.utils.DatabaseCleanUp;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 결제 시작 counter는 알림 비율의 분모다. 컨텍스트가 공유하는 MeterRegistry에 누적되므로 테스트 시작 값 대비 증가분으로 본다.
// 반영 실패 주입은 OrderTransactionTest의 테스트 설정을 그대로 쓴다(같은 컨텍스트 재사용).
@SpringBootTest
@Import(OrderTransactionTest.FailureInjectionConfig.class)
class PaymentStartedCounterIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-21T00:00:00Z");
    private static final String METRIC_NAME = "commerce.payment.started";
    private static final String PROVIDER = "test-pg";

    @Autowired private BrandApplicationService brands;
    @Autowired private ProductApplicationService products;
    @Autowired private OrderApplicationService orders;
    @Autowired private MutablePaymentClock clock;
    @Autowired private TestPgFixture pg;
    @Autowired private FailingPaymentRecordRepository failingPaymentRecordRepository;
    @Autowired private MeterRegistry meterRegistry;
    @Autowired private UserJpaRepository users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DatabaseCleanUp cleanup;
    private TestPgServer server;
    private long brandId;
    private long productId;

    @BeforeEach
    void prepare() {
        clock.set(NOW);
        server = pg.start();
        users.save(new UserJpaEntity(1));
        brandId = brands.create("브랜드").id();
        productId = products.create(brandId, "상품", 1000, 10).id();
        // 주입 저장소 설정을 공유하는 다른 테스트와 같이 상품 두 개를 둔다.
        products.create(brandId, "둘째 상품", 1000, 10);
        server.createBehavior(CreateBehavior.ACCEPT_PENDING);
    }

    @AfterEach
    void clean() throws Exception {
        failingPaymentRecordRepository.disarm();
        pg.close();
        jdbc.update("delete from order_items");
        cleanup.truncateAllTables();
    }

    @Test
    @DisplayName("애플리케이션이 시작되면 결제를 한 번도 시작하지 않아도 commerce.payment.started{provider=test-pg} 시계열이 등록되어 있다")
    void registersStartedCounterAtStartup() {
        Counter counter = meterRegistry.find(METRIC_NAME).tag("provider", PROVIDER).counter();

        assertThat(counter).as("%s{provider=%s} 등록", METRIC_NAME, PROVIDER).isNotNull();
    }

    @Test
    @DisplayName("새 주문의 confirm이 결제를 시작하면 started가 1 늘고 같은 시도 키로 다시 confirm해도 더 늘지 않는다")
    void countsOnlyNewlyStartedPayment() {
        double before = started();
        long orderId = newOrder(productId, 1);
        String attemptKey = "attempt-" + orderId;

        orders.confirm(1, orderId, TestPgFixture.command(attemptKey));
        double afterFirst = started();
        orders.confirm(1, orderId, TestPgFixture.command(attemptKey));

        assertThat(orderStatus(orderId)).isEqualTo("PAYMENT_PENDING");
        assertThat(server.createCalls()).as("PG create 호출").isEqualTo(1);
        assertThat(afterFirst - before).as("첫 confirm 뒤 started 증가분").isEqualTo(1.0);
        assertThat(started() - before).as("재요청 뒤 started 증가분").isEqualTo(1.0);
    }

    @Test
    @DisplayName("재고 부족으로 결제 시작 트랜잭션이 rollback되면 started가 늘지 않고 다음 정상 시작에서만 1 는다")
    void doesNotCountRolledBackStart() {
        long shortProductId = products.create(brandId, "재고 하나", 1000, 1).id();
        long shortOrderId = newOrder(shortProductId, 2);
        long normalOrderId = newOrder(productId, 1);
        double before = started();

        assertThatThrownBy(() -> orders.confirm(1, shortOrderId, TestPgFixture.command()))
            .isInstanceOf(RuleViolationException.class);
        double afterRollback = started();
        orders.confirm(1, normalOrderId, TestPgFixture.command());

        assertThat(orderStatus(shortOrderId)).as("rollback 후 새 조회").isEqualTo("DRAFT");
        assertThat(afterRollback - before).as("rollback 뒤 started 증가분").isEqualTo(0.0);
        assertThat(started() - before).as("정상 시작 뒤 started 증가분").isEqualTo(1.0);
    }

    @Test
    @DisplayName("결제 시작 트랜잭션이 commit된 뒤 PG 결과 반영이 실패해도 시작은 이미 일어났으므로 started가 1 는다")
    void countsCommittedStartEvenWhenResultApplyFails() {
        double before = started();
        long orderId = newOrder(productId, 1);
        // confirm에서 1번째 결제 기록 저장은 시작 트랜잭션, 2번째는 결과 반영이다.
        failingPaymentRecordRepository.failOnSave(2);

        orders.confirm(1, orderId, TestPgFixture.command());

        assertThat(failingPaymentRecordRepository.stateBeforeFailure()).as("실패 직전 DB로 보낸 상태").isNotNull();
        assertThat(jdbc.queryForObject("select provider_status from payment_records where order_id = ?", String.class, orderId))
            .as("rollback 후 새 조회").isEqualTo("SEND_STARTED");
        assertThat(started() - before).as("started 증가분").isEqualTo(1.0);
    }

    @Test
    @DisplayName("결제 시작 트랜잭션이 started 등록 뒤 commit 직전에 실패해 rollback되면 started가 늘지 않고 PG create도 보내지 않는다")
    void doesNotCountStartRolledBackAfterRegistration() {
        long orderId = newOrder(productId, 1);
        double before = started();
        // 1번째 결제 기록 저장이 시작 트랜잭션이다. started 등록은 그 저장 뒤라 등록 뒤 rollback이 된다.
        failingPaymentRecordRepository.failCommitAfterSave(1);

        assertThatThrownBy(() -> orders.confirm(1, orderId, TestPgFixture.command()))
            .hasMessage(FailingPaymentRecordRepository.INJECTED_COMMIT_FAILURE);

        assertThat(failingPaymentRecordRepository.stateBeforeFailure()).as("실패 직전 DB로 보낸 상태")
            .isEqualTo("order=PAYMENT_PENDING, payment=SEND_STARTED");
        assertThat(orderStatus(orderId)).as("rollback 후 새 조회").isEqualTo("DRAFT");
        assertThat(jdbc.queryForObject("select count(*) from payment_records", Long.class)).isZero();
        assertThat(server.createCalls()).as("PG create 호출").isZero();
        assertThat(started() - before).as("rollback 뒤 started 증가분").isEqualTo(0.0);
    }

    private long newOrder(long product, int quantity) {
        return orders.create(1, List.of(new OrderApplicationService.ItemRequest(product, quantity))).id();
    }

    private String orderStatus(long orderId) {
        return jdbc.queryForObject("select status from orders where id = ?", String.class, orderId);
    }

    // 등록 자체는 registersStartedCounterAtStartup이 본다. 여기서는 증가분만 보도록 미등록을 0으로 읽는다.
    private double started() {
        Counter counter = meterRegistry.find(METRIC_NAME).tag("provider", PROVIDER).counter();
        return counter == null ? 0.0 : counter.count();
    }
}
