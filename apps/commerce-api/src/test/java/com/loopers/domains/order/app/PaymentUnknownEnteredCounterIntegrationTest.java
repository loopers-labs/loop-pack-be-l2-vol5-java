package com.loopers.domains.order.app;

import com.loopers.domains.brand.app.BrandApplicationService;
import com.loopers.domains.order.app.OrderTransactionTest.FailingPaymentRecordRepository;
import com.loopers.domains.payment.domain.PaymentRecord;
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

// 결과 모름 유입 counter는 critical 알림의 유입 비율 분자다. gauge와 달리 만료가 빼 가도 줄지 않는다.
// 컨텍스트가 공유하는 MeterRegistry에 누적되므로 테스트 시작 값 대비 증가분으로 본다.
@SpringBootTest
@Import(OrderTransactionTest.FailureInjectionConfig.class)
class PaymentUnknownEnteredCounterIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-21T00:00:00Z");
    private static final String METRIC_NAME = "commerce.payment.unknown_entered";
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
    private long productId;

    @BeforeEach
    void prepare() {
        clock.set(NOW);
        server = pg.start();
        users.save(new UserJpaEntity(1));
        long brandId = brands.create("브랜드").id();
        productId = products.create(brandId, "상품", 1000, 10).id();
        // 주입 저장소 설정을 공유하는 다른 테스트와 같이 상품 두 개를 둔다.
        products.create(brandId, "둘째 상품", 1000, 10);
        // 거래는 만들고 500을 돌려줘 create 결과 모름이 된다.
        server.createBehavior(CreateBehavior.ERROR_500_AFTER_CREATE);
    }

    @AfterEach
    void clean() throws Exception {
        failingPaymentRecordRepository.disarm();
        pg.close();
        jdbc.update("delete from order_items");
        cleanup.truncateAllTables();
    }

    @Test
    @DisplayName("애플리케이션이 시작되면 결과 모름이 한 번도 없어도 commerce.payment.unknown_entered{provider=test-pg} 시계열이 등록되어 있다")
    void registersUnknownEnteredCounterAtStartup() {
        Counter counter = meterRegistry.find(METRIC_NAME).tag("provider", PROVIDER).counter();

        assertThat(counter).as("%s{provider=%s} 등록", METRIC_NAME, PROVIDER).isNotNull();
    }

    @Test
    @DisplayName("create 결과 모름으로 결제 기록이 처음 UNKNOWN이 되면 1 늘고 같은 기록에 UNKNOWN을 다시 보고해도 더 늘지 않는다")
    void countsOnlyFirstEntryIntoUnknown() {
        double before = unknownEntered();
        long orderId = newOrder();

        orders.confirm(1, orderId, TestPgFixture.command());
        double afterCreate = unknownEntered();
        orders.applyPaymentResult(orderId, new PaymentReport(PaymentRecord.ProviderStatus.UNKNOWN, null));

        assertThat(providerStatus(orderId)).isEqualTo("UNKNOWN");
        assertThat(afterCreate - before).as("create 결과 모름 뒤 증가분").isEqualTo(1.0);
        assertThat(unknownEntered() - before).as("UNKNOWN 재보고 뒤 증가분").isEqualTo(1.0);
    }

    @Test
    @DisplayName("UNKNOWN 전이를 반영하던 트랜잭션이 rollback되면 늘지 않고 다음 주문의 commit된 결과 모름에서만 1 는다")
    void doesNotCountRolledBackEntry() {
        long rolledBackOrderId = newOrder();
        long committedOrderId = newOrder();
        double before = unknownEntered();
        // confirm에서 1번째 결제 기록 저장은 시작 트랜잭션, 2번째는 결과 반영이다.
        failingPaymentRecordRepository.failOnSave(2);

        orders.confirm(1, rolledBackOrderId, TestPgFixture.command());
        double afterRollback = unknownEntered();
        orders.confirm(1, committedOrderId, TestPgFixture.command());

        assertThat(failingPaymentRecordRepository.stateBeforeFailure()).as("실패 직전 DB로 보낸 상태").isNotNull();
        assertThat(providerStatus(rolledBackOrderId)).as("rollback 후 새 조회").isEqualTo("SEND_STARTED");
        assertThat(providerStatus(committedOrderId)).isEqualTo("UNKNOWN");
        assertThat(afterRollback - before).as("rollback 뒤 증가분").isEqualTo(0.0);
        assertThat(unknownEntered() - before).as("commit된 결과 모름 뒤 증가분").isEqualTo(1.0);
    }

    @Test
    @DisplayName("결과 모름 반영 트랜잭션이 unknown_entered 등록 뒤 commit 직전에 실패해 rollback되면 늘지 않는다")
    void doesNotCountEntryRolledBackAfterRegistration() {
        long orderId = newOrder();
        double before = unknownEntered();
        // 2번째 결제 기록 저장이 결과 반영이다. unknown_entered 등록은 그 저장 뒤라 등록 뒤 rollback이 된다.
        failingPaymentRecordRepository.failCommitAfterSave(2);

        orders.confirm(1, orderId, TestPgFixture.command());

        assertThat(failingPaymentRecordRepository.stateBeforeFailure()).as("실패 직전 DB로 보낸 상태")
            .isEqualTo("order=PAYMENT_PENDING, payment=UNKNOWN");
        assertThat(providerStatus(orderId)).as("rollback 후 새 조회").isEqualTo("SEND_STARTED");
        assertThat(unknownEntered() - before).as("rollback 뒤 증가분").isEqualTo(0.0);
    }

    private long newOrder() {
        return orders.create(1, List.of(new OrderApplicationService.ItemRequest(productId, 1))).id();
    }

    private String providerStatus(long orderId) {
        return jdbc.queryForObject("select provider_status from payment_records where order_id = ?", String.class, orderId);
    }

    // 등록 자체는 registersUnknownEnteredCounterAtStartup이 본다. 여기서는 증가분만 보도록 미등록을 0으로 읽는다.
    private double unknownEntered() {
        Counter counter = meterRegistry.find(METRIC_NAME).tag("provider", PROVIDER).counter();
        return counter == null ? 0.0 : counter.count();
    }
}
