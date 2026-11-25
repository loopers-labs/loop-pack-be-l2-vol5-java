package com.loopers.domains.order.app;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.loopers.domains.brand.app.BrandApplicationService;
import com.loopers.domains.order.app.OrderTransactionTest.FailingScheduleRepository;
import com.loopers.domains.payment.app.PaymentExpiryPolicy;
import com.loopers.domains.payment.app.port.PaymentLookupScheduleRepository;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

// 결제 종결 결과 counter는 컨텍스트가 공유하는 MeterRegistry에 누적되므로 값 자체가 아니라 테스트 시작 값 대비 증가분으로 본다.
// 반영 실패 주입은 OrderTransactionTest의 테스트 설정을 그대로 쓴다(같은 컨텍스트 재사용).
@SpringBootTest
@Import(OrderTransactionTest.FailureInjectionConfig.class)
class PaymentOutcomeCounterIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-21T00:00:00Z");
    private static final String METRIC_NAME = "commerce.payment.outcome";
    private static final String SERVICE_LOGGER = OrderApplicationService.class.getName();
    private static final String CONFIRMED_LOG = "결제를 확정";
    private static final String FAILED_LOG = "결제 실패로 종결";

    @Autowired private BrandApplicationService brands;
    @Autowired private ProductApplicationService products;
    @Autowired private OrderApplicationService orders;
    @Autowired private MutablePaymentClock clock;
    @Autowired private TestPgFixture pg;
    @Autowired private PaymentExpiryPolicy paymentExpiryPolicy;
    @Autowired private PaymentLookupScheduleRepository schedules;
    @Autowired private FailingScheduleRepository failingScheduleRepository;
    @Autowired private MeterRegistry meterRegistry;
    @Autowired private UserJpaRepository users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DatabaseCleanUp cleanup;
    private TestPgServer server;
    private long productId;
    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void prepare() {
        clock.set(NOW);
        server = pg.start();
        users.save(new UserJpaEntity(1));
        long brand = brands.create("브랜드").id();
        productId = products.create(brand, "상품", 1000, 10).id();
        // 주입 저장소 설정을 공유하는 다른 테스트와 같이 상품 두 개를 둔다.
        products.create(brand, "둘째 상품", 1000, 10);
        logs = new ListAppender<>();
        logs.start();
        logger().addAppender(logs);
    }

    @AfterEach
    void clean() throws Exception {
        logger().detachAppender(logs);
        failingScheduleRepository.disarmDelete();
        pg.close();
        jdbc.update("delete from order_items");
        cleanup.truncateAllTables();
    }

    @Test
    @DisplayName("확정·PG 결제 실패·만료로 주문이 처음 종결되면 confirmed·failed·expired counter가 각각 1씩 늘고 not_sent는 늘지 않는다")
    void countsEachOutcomeOnceWhenOrderFirstCloses() {
        double confirmedBefore = outcome("confirmed");
        double failedBefore = outcome("failed");
        double notSentBefore = outcome("not_sent");
        double expiredBefore = outcome("expired");
        long confirmedOrder = newOrder();
        long failedOrder = newOrder();
        long expiredOrder = newOrder();

        server.createBehavior(CreateBehavior.ACCEPT_SUCCESS);
        orders.confirm(1, confirmedOrder, TestPgFixture.command());
        server.createBehavior(CreateBehavior.ACCEPT_FAILED);
        orders.confirm(1, failedOrder, TestPgFixture.command());
        server.createBehavior(CreateBehavior.ACCEPT_PENDING);
        orders.confirm(1, expiredOrder, TestPgFixture.command());
        clock.set(NOW.plus(paymentExpiryPolicy.timeout()));
        orders.expireOverduePayments();

        assertThat(orderStatus(confirmedOrder)).isEqualTo("CONFIRMED");
        assertThat(orderStatus(failedOrder)).isEqualTo("PAYMENT_FAILED");
        assertThat(orderStatus(expiredOrder)).isEqualTo("PAYMENT_EXPIRED");
        assertThat(outcome("confirmed") - confirmedBefore).as("confirmed 증가분").isEqualTo(1.0);
        assertThat(outcome("failed") - failedBefore).as("failed 증가분").isEqualTo(1.0);
        assertThat(outcome("expired") - expiredBefore).as("expired 증가분").isEqualTo(1.0);
        assertThat(outcome("not_sent") - notSentBefore).as("not_sent 증가분").isEqualTo(0.0);
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({"ACCEPT_FAILED, failed, not_sent, FAILED, FAILED",
        "REJECT_BEFORE_CREATE, not_sent, failed, NOT_SENT, REJECTED_NO_EFFECT"})
    @DisplayName("PG가 거래를 만들고 FAILED로 확정하면 failed, 거래를 만들기 전에 거절하면 not_sent로 1번만 세고 실패 종결 INFO의 result도 같다")
    void countsCreateRejectionByWhetherTransactionWasCreated(CreateBehavior behavior, String counted, String notCounted,
        String loggedResult, String providerStatus) {
        double countedBefore = outcome(counted);
        double notCountedBefore = outcome(notCounted);
        long orderId = newOrder();
        server.createBehavior(behavior);
        logs.list.clear();

        orders.confirm(1, orderId, TestPgFixture.command());

        assertThat(orderStatus(orderId)).isEqualTo("PAYMENT_FAILED");
        assertThat(outcome(counted) - countedBefore).as("%s 증가분", counted).isEqualTo(1.0);
        assertThat(outcome(notCounted) - notCountedBefore).as("%s 증가분", notCounted).isEqualTo(0.0);
        assertThat(failedClosingLog().getFormattedMessage())
            .contains("orderId=" + orderId + ", result=" + loggedResult + ", providerStatus=" + providerStatus);
    }

    @Test
    @DisplayName("모든 PG에 결제 생성 요청이 닿지 못해 결제 실패로 종결되면 not_sent counter가 1 늘고 failed는 늘지 않으며 실패 종결 INFO의 result는 NOT_SENT다")
    void countsAllNotSentAsNotSent() throws Exception {
        double notSentBefore = outcome("not_sent");
        double failedBefore = outcome("failed");
        long orderId = newOrder();
        // 서버를 먼저 닫아 연결 거절로 요청이 나가지 못하게 한다. 등록된 PG는 test-pg 하나라 미발송이 곧 전체 미발송이다.
        server.close();
        logs.list.clear();

        orders.confirm(1, orderId, TestPgFixture.command());

        assertThat(orderStatus(orderId)).isEqualTo("PAYMENT_FAILED");
        assertThat(jdbc.queryForObject("select provider_status from payment_records where order_id = ?", String.class, orderId))
            .isEqualTo("ALL_NOT_SENT");
        assertThat(outcome("not_sent") - notSentBefore).as("not_sent 증가분").isEqualTo(1.0);
        assertThat(outcome("failed") - failedBefore).as("failed 증가분").isEqualTo(0.0);
        assertThat(failedClosingLog().getFormattedMessage())
            .contains("orderId=" + orderId + ", result=NOT_SENT, providerStatus=ALL_NOT_SENT");
    }

    @Test
    @DisplayName("이미 확정된 주문을 수동 재조회 3번과 callback 1번으로 다시 확인해도 confirmed counter는 확정 때 1만 늘고 더 늘지 않는다")
    void doesNotCountRepeatedVerificationOfClosedOrder() {
        double confirmedBefore = outcome("confirmed");
        long orderId = newOrder();
        server.createBehavior(CreateBehavior.ACCEPT_SUCCESS);
        orders.confirm(1, orderId, TestPgFixture.command());
        String providerOrderId = jdbc.queryForObject("select provider_order_id from payment_records where order_id = ?",
            String.class, orderId);
        String transactionKey = jdbc.queryForObject("select transaction_key from payment_records where order_id = ?",
            String.class, orderId);

        orders.reconcilePayment(orderId);
        orders.reconcilePayment(orderId);
        orders.reconcilePayment(orderId);
        orders.receivePaymentCallback(providerOrderId, transactionKey);

        assertThat(orderStatus(orderId)).isEqualTo("CONFIRMED");
        assertThat(server.lookupCalls()).as("PG 조회 횟수").isEqualTo(4);
        assertThat(outcome("confirmed") - confirmedBefore).as("confirmed 증가분").isEqualTo(1.0);
    }

    @Test
    @DisplayName("이미 확정된 주문에 PG가 FAILED를 보고해 반대 결과로 거절되면 confirmed·failed·not_sent counter 모두 확정 때 이후 더 늘지 않는다")
    void doesNotCountConflictingResultOnClosedOrder() {
        double confirmedBefore = outcome("confirmed");
        double failedBefore = outcome("failed");
        double notSentBefore = outcome("not_sent");
        long orderId = newOrder();
        server.createBehavior(CreateBehavior.ACCEPT_SUCCESS);
        orders.confirm(1, orderId, TestPgFixture.command());
        String transactionKey = jdbc.queryForObject("select transaction_key from payment_records where order_id = ?",
            String.class, orderId);
        server.changeStatus(transactionKey, "FAILED");

        orders.reconcilePayment(orderId);

        assertThat(orderStatus(orderId)).isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("select count(*) from payment_anomalies where order_id = ? and kind = 'CONFLICTING_RESULT'",
            Long.class, orderId)).as("반대 결과 이상 기록").isEqualTo(1L);
        assertThat(outcome("confirmed") - confirmedBefore).as("confirmed 증가분").isEqualTo(1.0);
        assertThat(outcome("failed") - failedBefore).as("failed 증가분").isEqualTo(0.0);
        assertThat(outcome("not_sent") - notSentBefore).as("not_sent 증가분").isEqualTo(0.0);
    }

    @Test
    @DisplayName("자동 재조회가 확정까지 반영한 뒤 같은 트랜잭션이 rollback되면 confirmed counter와 확정 INFO가 남지 않고 주문은 결제 대기다")
    void doesNotCountOrLogWhenClosingTransactionRollsBack() {
        long orderId = newOrder();
        String providerOrderId = String.format("LP-ORD-%010d", orderId);
        pg.loseResponseAfterSuccess(providerOrderId);
        orders.confirm(1, orderId, TestPgFixture.command());
        assertThat(orderStatus(orderId)).isEqualTo("PAYMENT_PENDING");
        clock.set(schedules.findByOrderId(orderId).orElseThrow().nextLookupAt());
        // 등록 여부는 마지막 단언에서 본다. 여기서 먼저 실패하면 rollback·로그 단언까지 가지 못한다.
        Counter confirmed = meterRegistry.find(METRIC_NAME).tag("result", "confirmed").counter();
        double confirmedBefore = confirmed == null ? 0.0 : confirmed.count();
        failingScheduleRepository.failOnDelete(2);
        logs.list.clear();

        orders.reconcileDuePayments();

        assertThat(failingScheduleRepository.orderStatusBeforeDeleteFailure()).as("실패 직전 DB로 보낸 주문 상태")
            .isEqualTo("CONFIRMED");
        assertThat(orderStatus(orderId)).as("rollback 후 새 조회").isEqualTo("PAYMENT_PENDING");
        assertThat(logs.list).as("확정 INFO")
            .noneMatch(event -> event.getLevel() == Level.INFO && event.getFormattedMessage().contains(CONFIRMED_LOG));
        assertThat(outcome("confirmed") - confirmedBefore).as("confirmed 증가분").isEqualTo(0.0);
    }

    private long newOrder() {
        return orders.create(1, List.of(new OrderApplicationService.ItemRequest(productId, 1))).id();
    }

    private ILoggingEvent failedClosingLog() {
        List<ILoggingEvent> found = logs.list.stream()
            .filter(event -> event.getLevel() == Level.INFO && event.getFormattedMessage().contains(FAILED_LOG))
            .toList();
        assertThat(found).as("실패 종결 INFO").hasSize(1);
        return found.get(0);
    }

    private String orderStatus(long orderId) {
        return jdbc.queryForObject("select status from orders where id = ?", String.class, orderId);
    }

    // counter는 시작 시 등록되어야 하므로 없으면 0으로 보지 않고 실패한다.
    private double outcome(String result) {
        Counter counter = meterRegistry.find(METRIC_NAME).tag("result", result).counter();
        assertThat(counter).as("%s{result=%s} 등록", METRIC_NAME, result).isNotNull();
        return counter.count();
    }

    private static Logger logger() {
        return (Logger) LoggerFactory.getLogger(SERVICE_LOGGER);
    }
}
