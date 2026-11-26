package com.loopers.domains.payment.in;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.loopers.common.domain.Money;
import com.loopers.domains.brand.app.BrandApplicationService;
import com.loopers.domains.order.app.OrderApplicationService;
import com.loopers.domains.payment.app.RefundTrackingService;
import com.loopers.domains.payment.app.port.PaymentLookupCircuitState;
import com.loopers.domains.payment.app.port.PaymentRefundCaseRepository;
import com.loopers.domains.payment.domain.PaymentRecord;
import com.loopers.domains.payment.domain.RefundCase;
import com.loopers.domains.payment.out.PaymentCircuitBreakers;
import com.loopers.domains.payment.out.TestPgServer;
import com.loopers.domains.payment.out.TestPgServer.CancelBehavior;
import com.loopers.domains.payment.out.TestPgServer.CreateBehavior;
import com.loopers.domains.product.app.ProductApplicationService;
import com.loopers.domains.user.out.UserJpaEntity;
import com.loopers.domains.user.out.UserJpaRepository;
import com.loopers.support.pg.MutablePaymentClock;
import com.loopers.support.pg.TestPgFixture;
import com.loopers.utils.DatabaseCleanUp;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 만료된 PG 결제의 환불 추적 한 회차를 실제 DB·test-pg로 본다(10주차 5단계 F2~F7·F9·F10, 경계 로그).
// 스케줄러는 test 프로필에서 꺼져 있어 서비스 회차를 직접 부른다. 대상은 서비스 트랜잭션이 끝난 뒤 포트로 새로 읽는다.
// counter는 컨텍스트가 공유하는 MeterRegistry에 누적되므로 테스트 시작 값 대비 증가분으로 본다.
// 전용 테스트 설정이라 캐시에 남으면 DB 연결을 계속 붙잡는다(전체 검사에서 Too many connections). 클래스가 끝나면 닫는다.
@DirtiesContext
@SpringBootTest
@Import(RefundTrackingIntegrationTest.TimeConfig.class)
class RefundTrackingIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-21T00:00:00Z");
    private static final Instant EXPIRES_AT = NOW.plus(Duration.ofMinutes(2));
    private static final String OUTCOME_METRIC = "commerce.payment.refund_outcome";
    private static final String CASES_METRIC = "commerce.payment.refund_cases";
    private static final String SECRET_BODY_MARKER = "PG-SECRET-BODY";

    @Autowired private BrandApplicationService brands;
    @Autowired private ProductApplicationService products;
    @Autowired private OrderApplicationService orders;
    @Autowired private RefundTrackingService refundTracking;
    @Autowired private RefundCaseMetrics refundCaseMetrics;
    @Autowired private PaymentRefundCaseRepository refundCases;
    @Autowired private MeterRegistry meterRegistry;
    @Autowired private MutablePaymentClock clock;
    @Autowired private TestPgFixture pg;
    @Autowired private UserJpaRepository users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DatabaseCleanUp cleanup;
    @Autowired private FailingCircuitState failingCircuitState;
    private final Logger serviceLogger = (Logger) LoggerFactory.getLogger(RefundTrackingService.class);
    private ListAppender<ILoggingEvent> logs;
    private TestPgServer server;
    private long productId;

    @BeforeEach
    void prepare() {
        clock.set(NOW);
        server = pg.start();
        users.save(new UserJpaEntity(1));
        long brand = brands.create("브랜드").id();
        productId = products.create(brand, "상품", 1000, 30).id();
        logs = new ListAppender<>();
        logs.start();
        serviceLogger.addAppender(logs);
    }

    @AfterEach
    void clean() throws Exception {
        serviceLogger.detachAppender(logs);
        failingCircuitState.disarm();
        pg.close();
        jdbc.update("delete from order_items");
        cleanup.truncateAllTables();
    }

    @Test
    @DisplayName("만료 뒤 PG가 단일 SUCCESS면 한 회차에 취소를 정확히 한 번 보내 REFUNDED가 되고 refunded가 1 늘며 다음 회차에도 취소는 그대로 1이다")
    void refundsLateSuccessExactlyOnce() {
        double refundedBefore = outcome("refunded");
        long orderId = expiredOrder();
        server.changeStatus(transactionKey(orderId), "SUCCESS");

        refundTracking.trackDueCases();
        clock.advance(Duration.ofHours(2));
        refundTracking.trackDueCases();

        RefundCase refundCase = refundCase(orderId);
        assertThat(refundCase.getStatus()).isEqualTo(RefundCase.Status.REFUNDED);
        assertThat(refundCase.getRefundRequestedAt()).isEqualTo(EXPIRES_AT);
        assertThat(refundCase.getConcludedAt()).isEqualTo(EXPIRES_AT);
        assertThat(pg.cancelCalls(providerOrderId(orderId))).isEqualTo(1);
        assertThat(pgStatus(orderId)).isEqualTo("CANCELED");
        assertThat(outcome("refunded") - refundedBefore).isEqualTo(1.0);
        assertThat(orderStatus(orderId)).isEqualTo("PAYMENT_EXPIRED");
    }

    @Test
    @DisplayName("취소 응답을 잃으면 REFUND_UNKNOWN이 되고 다음 회차는 다시 보내지 않고 조회로 CANCELED를 확인해 REFUNDED가 되며 취소 호출은 1에서 늘지 않는다")
    void resolvesLostCancelByLookup() {
        long orderId = expiredOrder();
        server.changeStatus(transactionKey(orderId), "SUCCESS");
        pg.loseCancelResponse(providerOrderId(orderId));

        refundTracking.trackDueCases();

        assertThat(refundCase(orderId).getStatus()).isEqualTo(RefundCase.Status.REFUND_UNKNOWN);
        assertThat(pg.cancelCalls(providerOrderId(orderId))).isEqualTo(1);

        clock.advance(Duration.ofMinutes(1));
        refundTracking.trackDueCases();

        assertThat(refundCase(orderId).getStatus()).isEqualTo(RefundCase.Status.REFUNDED);
        assertThat(pg.cancelCalls(providerOrderId(orderId))).isEqualTo(1);
    }

    @Test
    @DisplayName("REFUND_REQUESTED를 commit한 뒤 취소를 보내기 전에 멈추면 다음 회차들은 조회만 하고 취소를 한 번도 보내지 않으며 기한에 사람에게 넘긴다")
    void neverSendsCancelAfterInterruptedRequest() {
        long orderId = expiredOrder();
        server.changeStatus(transactionKey(orderId), "SUCCESS");
        pg.crashBeforeCancel();

        assertThatThrownBy(refundTracking::trackDueCases).isInstanceOf(TestPgFixture.InjectedCrash.class);

        assertThat(refundCase(orderId).getStatus()).isEqualTo(RefundCase.Status.REFUND_REQUESTED);
        assertThat(pg.cancelCalls(providerOrderId(orderId))).isZero();

        pg.resumeCancel();
        long lookupsBefore = server.lookupCalls();
        clock.advance(Duration.ofMinutes(1));
        refundTracking.trackDueCases();

        assertThat(server.lookupCalls() - lookupsBefore).isEqualTo(1);
        assertThat(refundCase(orderId).getStatus()).isEqualTo(RefundCase.Status.REFUND_REQUESTED);
        assertThat(refundCase(orderId).getCheckAttempts()).isEqualTo(1);
        assertThat(pg.cancelCalls(providerOrderId(orderId))).isZero();

        clock.set(EXPIRES_AT.plus(Duration.ofDays(2)));
        refundTracking.trackDueCases();

        assertThat(refundCase(orderId).getStatus()).isEqualTo(RefundCase.Status.HANDED_TO_HUMAN);
        assertThat(refundCase(orderId).getReason()).isEqualTo(RefundCase.HANDOFF_DEADLINE);
        assertThat(pg.cancelCalls(providerOrderId(orderId))).isZero();
    }

    @Test
    @DisplayName("만료 뒤 PG가 단일 FAILED면 취소 없이 CLOSED_NO_CHARGE로 닫고 closed_no_charge가 1 는다")
    void closesFailedPaymentWithoutRefund() {
        double closedBefore = outcome("closed_no_charge");
        long orderId = expiredOrder();
        server.changeStatus(transactionKey(orderId), "FAILED");

        refundTracking.trackDueCases();

        assertThat(refundCase(orderId).getStatus()).isEqualTo(RefundCase.Status.CLOSED_NO_CHARGE);
        assertThat(refundCase(orderId).getConcludedAt()).isEqualTo(EXPIRES_AT);
        assertThat(pg.cancelCalls(providerOrderId(orderId))).isZero();
        assertThat(outcome("closed_no_charge") - closedBefore).isEqualTo(1.0);
    }

    @ParameterizedTest(name = "조회 장면={0}")
    @ValueSource(strings = {"pending", "zero", "lookup-error"})
    @DisplayName("PENDING·0건·조회 실패가 이어지면 TRACKING으로 간격을 늘리며 추적하다가 이관 기한(2일)에 조회 없이 사람에게 넘기고 counter·gauge가 바뀐다")
    void handsOverAtDeadlineWhileInconclusive(String scene) {
        double handedBefore = outcome("handed_to_human");
        long orderId = expiredOrder();
        switch (scene) {
            case "zero" -> server.removeTransaction(transactionKey(orderId));
            case "lookup-error" -> server.lookupErrorFor(providerOrderId(orderId));
            default -> {
            }
        }

        refundTracking.trackDueCases();

        RefundCase tracked = refundCase(orderId);
        assertThat(tracked.getStatus()).isEqualTo(RefundCase.Status.TRACKING);
        assertThat(tracked.getCheckAttempts()).isEqualTo(1);
        assertThat(tracked.getNextCheckAt()).isEqualTo(EXPIRES_AT.plus(Duration.ofMinutes(1)));

        clock.set(EXPIRES_AT.plus(Duration.ofDays(2)));
        long lookupsBefore = server.lookupCalls();
        refundTracking.trackDueCases();
        refundCaseMetrics.refresh();

        RefundCase handed = refundCase(orderId);
        assertThat(handed.getStatus()).isEqualTo(RefundCase.Status.HANDED_TO_HUMAN);
        assertThat(handed.getReason()).isEqualTo(RefundCase.HANDOFF_DEADLINE);
        assertThat(server.lookupCalls() - lookupsBefore).isZero();
        assertThat(pg.cancelCalls(providerOrderId(orderId))).isZero();
        assertThat(outcome("handed_to_human") - handedBefore).isEqualTo(1.0);
        assertThat(gauge("handed_to_human")).isEqualTo(1.0);
        assertThat(gauge("tracking")).isZero();
    }

    @ParameterizedTest(name = "조회 장면={0}")
    @ValueSource(strings = {"amount", "order", "multiple"})
    @DisplayName("금액·주문이 다른 거래나 복수 거래가 보이면 취소하지 않고 즉시 사유를 남겨 사람에게 넘긴다")
    void handsMismatchToHumanImmediately(String scene) {
        long orderId = expiredOrder();
        String key = transactionKey(orderId);
        String expectedReason = RefundCase.TRANSACTION_MISMATCH;
        switch (scene) {
            case "amount" -> server.replaceTransaction(key, providerOrderId(orderId), 1001, "SUCCESS");
            case "order" -> {
                server.changeStatus(key, "SUCCESS");
                server.lookupBehavior(TestPgServer.LookupBehavior.WRONG_ORDER_ID);
            }
            default -> {
                server.changeStatus(key, "SUCCESS");
                server.addTransaction(providerOrderId(orderId), 1000, "SUCCESS");
                expectedReason = RefundCase.MULTIPLE_TRANSACTIONS;
            }
        }

        refundTracking.trackDueCases();

        RefundCase refundCase = refundCase(orderId);
        assertThat(refundCase.getStatus()).isEqualTo(RefundCase.Status.HANDED_TO_HUMAN);
        assertThat(refundCase.getReason()).isEqualTo(expectedReason);
        assertThat(refundCase.getConcludedAt()).isEqualTo(EXPIRES_AT);
        assertThat(pg.cancelCalls(providerOrderId(orderId))).isZero();
    }

    @Test
    @DisplayName("기한이 된 대상이 상한 20건보다 많으면 한 회차에 다음 조회 시각이 이른 20건만 조회하고 나머지는 손대지 않는다")
    void looksUpAtMostBatchLimitPerRun() {
        for (int index = 1; index <= 25; index++) {
            long orderId = 1000 + index;
            refundCases.save(tracking(orderId, NOW.minusSeconds(100 - index)));
            server.addTransaction(providerOrderId(orderId), 1000, "PENDING");
        }
        long lookupsBefore = server.lookupCalls();

        refundTracking.trackDueCases();

        assertThat(server.lookupCalls() - lookupsBefore).isEqualTo(RefundTrackingService.BATCH_SIZE);
        for (int index = 1; index <= 20; index++) {
            assertThat(refundCase(1000 + index).getCheckAttempts()).as("이른 대상 %d", index).isEqualTo(1);
        }
        for (int index = 21; index <= 25; index++) {
            RefundCase untouched = refundCase(1000 + index);
            assertThat(untouched.getCheckAttempts()).as("상한 밖 대상 %d", index).isZero();
            assertThat(untouched.getNextCheckAt()).isEqualTo(NOW.minusSeconds(100 - index));
        }
    }

    @ParameterizedTest(name = "결론 상태={0}")
    @EnumSource(value = RefundCase.Status.class, names = {"REFUNDED", "CLOSED_NO_CHARGE", "HANDED_TO_HUMAN"})
    @DisplayName("결론 난 대상은 다음 조회 시각·이관 기한이 지나 있어도 조회·취소하지 않고 행이 그대로다")
    void leavesConcludedCasesUntouched(RefundCase.Status status) {
        long orderId = 2001;
        clock.set(NOW.plus(Duration.ofDays(3)));
        refundCases.save(RefundCase.restore(orderId, TestPgFixture.PROVIDER_ID, providerOrderId(orderId), new Money(1000), status,
            PaymentRecord.ProviderStatus.PENDING, "TR-X", NOW, NOW.plus(RefundCase.HANDOFF_AFTER), NOW.plus(RefundCase.PROMISE_AFTER),
            NOW, 3, NOW, NOW, "PRIOR"));
        server.addTransaction(providerOrderId(orderId), 1000, "SUCCESS");
        Map<String, Object> before = row(orderId);
        long lookupsBefore = server.lookupCalls();

        refundTracking.trackDueCases();

        assertThat(server.lookupCalls() - lookupsBefore).isZero();
        assertThat(pg.cancelCalls(providerOrderId(orderId))).isZero();
        assertThat(row(orderId)).isEqualTo(before);
    }

    @Test
    @DisplayName("환불 요청은 INFO, 취소 결과 모름은 WARN으로 주문 열쇠(MDC orderId)와 함께 남기고 PG 응답 본문은 어느 로그에도 남기지 않는다")
    void logsRefundBoundariesWithoutResponseBody() {
        long orderId = expiredOrder();
        server.changeStatus(transactionKey(orderId), "SUCCESS");
        server.cancelBehavior(CancelBehavior.ERROR_500_WITH_SECRET_BODY);

        refundTracking.trackDueCases();

        assertThat(refundCase(orderId).getStatus()).isEqualTo(RefundCase.Status.REFUND_UNKNOWN);
        assertThat(events(Level.INFO)).as("환불 요청 INFO").anySatisfy(event -> assertOrderKey(event, orderId));
        assertThat(events(Level.WARN)).as("결과 모름 WARN").anySatisfy(event -> assertOrderKey(event, orderId));
        assertThat(logs.list).allSatisfy(event -> {
            assertThat(event.getFormattedMessage()).doesNotContain(SECRET_BODY_MARKER).doesNotContain(TestPgFixture.CARD_NO);
            assertThat(event.getThrowableProxy()).isNull();
        });
    }

    @Test
    @DisplayName("기한 이관은 사유와 함께 WARN, 건별 처리 실패는 원인 종류만 ERROR로 주문 열쇠와 함께 남기고 예외 메시지·스택은 남기지 않는다")
    void logsHandoffAndPerCaseFailure() {
        long failing = expiredOrder();
        server.changeStatus(transactionKey(failing), "SUCCESS");
        failingCircuitState.failNextCheck();

        refundTracking.trackDueCases();

        assertThat(events(Level.ERROR)).as("건별 실패 ERROR").singleElement().satisfies(event -> {
            assertOrderKey(event, failing);
            assertThat(event.getFormattedMessage()).contains("causeType=IllegalStateException").doesNotContain("주입된");
            assertThat(event.getThrowableProxy()).isNull();
        });

        clock.set(EXPIRES_AT.plus(Duration.ofDays(2)));
        logs.list.clear();
        refundTracking.trackDueCases();

        assertThat(events(Level.WARN)).as("사람 이관 WARN").singleElement().satisfies(event -> {
            assertOrderKey(event, failing);
            assertThat(event.getFormattedMessage()).contains(RefundCase.HANDOFF_DEADLINE);
        });
    }

    private long expiredOrder() {
        long orderId = orders.create(1, List.of(new OrderApplicationService.ItemRequest(productId, 1))).id();
        server.createBehavior(CreateBehavior.ACCEPT_PENDING);
        orders.confirm(1, orderId, TestPgFixture.command());
        clock.set(EXPIRES_AT);
        orders.expireOverduePayments();
        assertThat(orderStatus(orderId)).isEqualTo("PAYMENT_EXPIRED");
        assertThat(refundCase(orderId).getStatus()).isEqualTo(RefundCase.Status.TRACKING);
        return orderId;
    }

    private static RefundCase tracking(long orderId, Instant nextCheckAt) {
        return RefundCase.restore(orderId, TestPgFixture.PROVIDER_ID, providerOrderId(orderId), new Money(1000),
            RefundCase.Status.TRACKING, PaymentRecord.ProviderStatus.PENDING, null, NOW, NOW.plus(RefundCase.HANDOFF_AFTER),
            NOW.plus(RefundCase.PROMISE_AFTER), nextCheckAt, 0, null, null, null);
    }

    private RefundCase refundCase(long orderId) {
        return refundCases.findByOrderId(orderId).orElseThrow();
    }

    private Map<String, Object> row(long orderId) {
        return jdbc.queryForMap("select * from payment_refund_cases where order_id = ?", orderId);
    }

    private static String providerOrderId(long orderId) {
        return String.format("LP-ORD-%010d", orderId);
    }

    private String transactionKey(long orderId) {
        String providerOrderId = providerOrderId(orderId);
        return server.transactions().stream()
            .filter(transaction -> transaction.orderId().equals(providerOrderId))
            .findFirst().orElseThrow().transactionKey();
    }

    private String pgStatus(long orderId) {
        String key = transactionKey(orderId);
        return server.transactions().stream()
            .filter(transaction -> transaction.transactionKey().equals(key))
            .findFirst().orElseThrow().status();
    }

    private String orderStatus(long orderId) {
        return jdbc.queryForObject("select status from orders where id = ?", String.class, orderId);
    }

    private double outcome(String result) {
        Counter counter = meterRegistry.find(OUTCOME_METRIC).tag("result", result).counter();
        return counter == null ? 0 : counter.count();
    }

    private double gauge(String status) {
        Gauge gauge = meterRegistry.find(CASES_METRIC).tag("status", status).gauge();
        return gauge == null ? Double.NaN : gauge.value();
    }

    private List<ILoggingEvent> events(Level level) {
        return logs.list.stream().filter(event -> event.getLevel() == level).toList();
    }

    private static void assertOrderKey(ILoggingEvent event, long orderId) {
        assertThat(event.getMDCPropertyMap()).containsEntry("orderId", String.valueOf(orderId));
    }

    @TestConfiguration
    static class TimeConfig {
        @Bean
        @Primary
        MutablePaymentClock refundTrackingClock() {
            return new MutablePaymentClock();
        }

        @Bean
        @Primary
        FailingCircuitState failingCircuitState(PaymentCircuitBreakers delegate) {
            return new FailingCircuitState(delegate);
        }
    }

    // 건별 처리 실패를 만드는 테스트 설정이다. 회로 확인은 조회·취소 예외 처리 밖(잠금 트랜잭션 안)이라 예외가 회차의 건별 catch까지 간다.
    static class FailingCircuitState implements PaymentLookupCircuitState {
        private final PaymentLookupCircuitState delegate;
        private final AtomicBoolean failNext = new AtomicBoolean();

        FailingCircuitState(PaymentLookupCircuitState delegate) {
            this.delegate = delegate;
        }

        void failNextCheck() {
            failNext.set(true);
        }

        void disarm() {
            failNext.set(false);
        }

        @Override
        public boolean blocksLookup(String providerId) {
            if (failNext.getAndSet(false)) {
                throw new IllegalStateException("주입된 회로 확인 실패");
            }
            return delegate.blocksLookup(providerId);
        }
    }
}
