package com.loopers.domains.payment.in;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.loopers.common.domain.Money;
import com.loopers.domains.brand.app.BrandApplicationService;
import com.loopers.domains.order.app.OrderApplicationService;
import com.loopers.domains.payment.app.RefundTrackingService;
import com.loopers.domains.payment.app.port.LookupJitter;
import com.loopers.domains.payment.app.port.PaymentLookupCircuitState;
import com.loopers.domains.payment.app.port.PaymentRefundCaseRepository;
import com.loopers.domains.payment.domain.PaymentRecord;
import com.loopers.domains.payment.domain.RefundCase;
import com.loopers.domains.payment.domain.RefundCase.MoneyStatus;
import com.loopers.domains.payment.domain.RefundCase.Owner;
import com.loopers.domains.payment.domain.RefundCase.RefundActor;
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

// 만료된 PG 결제의 환불 추적 한 회차를 실제 DB·test-pg로 본다(10주차 5단계 F2~F7·F9·F10, 경계 로그, 5b S2·S3·S4).
// 스케줄러는 test 프로필에서 꺼져 있어 서비스 회차를 직접 부른다. 대상은 서비스 트랜잭션이 끝난 뒤 포트로 새로 읽는다.
// counter는 컨텍스트가 공유하는 MeterRegistry에 누적되므로 테스트 시작 값 대비 증가분으로 본다.
// 전용 테스트 설정이라 캐시에 남으면 DB 연결을 계속 붙잡는다(전체 검사에서 Too many connections). 클래스가 끝나면 닫는다.
@DirtiesContext
@SpringBootTest
@Import(RefundTrackingIntegrationTest.TimeConfig.class)
class RefundTrackingIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-21T00:00:00Z");
    private static final Instant EXPIRES_AT = NOW.plus(Duration.ofMinutes(2));
    private static final Instant FIRST_CHECK_AT = EXPIRES_AT.plus(RefundCase.FIRST_CHECK_DELAY);
    private static final String OUTCOME_METRIC = "commerce.payment.refund_outcome";
    private static final String ASSIGNED_METRIC = "commerce.payment.refund_person_assigned";
    private static final String CASES_METRIC = "commerce.payment.refund_cases";
    private static final String PERSON_OPEN_METRIC = "commerce.payment.refund_person_open";
    private static final String OLDEST_AGE_METRIC = "commerce.payment.refund_requested_oldest_age_seconds";
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
    @DisplayName("만료 뒤 PG가 단일 SUCCESS면 한 회차에 취소를 정확히 한 번 보내 환불됨(SYSTEM)이 되고 환불됨(SYSTEM) counter가 1 늘며 다음 회차에도 취소는 그대로 1이다")
    void refundsLateSuccessExactlyOnce() {
        double refundedBefore = outcome("refunded", "system");
        long orderId = expiredOrder();
        server.changeStatus(transactionKey(orderId), "SUCCESS");

        refundTracking.trackDueCases();
        clock.advance(Duration.ofHours(2));
        refundTracking.trackDueCases();

        RefundCase refundCase = refundCase(orderId);
        assertThat(refundCase.getMoneyStatus()).isEqualTo(MoneyStatus.REFUNDED);
        assertThat(refundCase.getRefundActor()).isEqualTo(RefundActor.SYSTEM);
        assertThat(refundCase.getOwner()).isEqualTo(Owner.AUTO);
        assertThat(refundCase.getRefundRequestedAt()).isEqualTo(FIRST_CHECK_AT);
        assertThat(refundCase.getConcludedAt()).isEqualTo(FIRST_CHECK_AT);
        assertThat(pg.cancelCalls(providerOrderId(orderId))).isEqualTo(1);
        assertThat(pgStatus(orderId)).isEqualTo("CANCELED");
        assertThat(outcome("refunded", "system") - refundedBefore).isEqualTo(1.0);
        assertThat(orderStatus(orderId)).isEqualTo("PAYMENT_EXPIRED");
    }

    @Test
    @DisplayName("취소 응답을 잃으면 환불 결과 모름이 되고 다음 회차는 다시 보내지 않고 조회로 CANCELED를 확인해 환불됨(SYSTEM)이 되며 취소 호출은 1에서 늘지 않는다")
    void resolvesLostCancelByLookup() {
        long orderId = expiredOrder();
        server.changeStatus(transactionKey(orderId), "SUCCESS");
        pg.loseCancelResponse(providerOrderId(orderId));

        refundTracking.trackDueCases();

        assertThat(refundCase(orderId).getMoneyStatus()).isEqualTo(MoneyStatus.REFUND_UNKNOWN);
        assertThat(pg.cancelCalls(providerOrderId(orderId))).isEqualTo(1);

        clock.advance(Duration.ofMinutes(1));
        refundTracking.trackDueCases();

        assertThat(refundCase(orderId).getMoneyStatus()).isEqualTo(MoneyStatus.REFUNDED);
        assertThat(refundCase(orderId).getRefundActor()).isEqualTo(RefundActor.SYSTEM);
        assertThat(pg.cancelCalls(providerOrderId(orderId))).isEqualTo(1);
    }

    @Test
    @DisplayName("환불 요청함을 commit한 뒤 취소 전에 멈추면 다음 회차들은 조회만 하고, 기한에 돈은 환불 요청함 그대로 담당만 사람이 되며 그 뒤에도 조회만 해 취소를 한 번도 보내지 않으며 요청 정체 gauge가 요청 뒤 경과 초다")
    void neverSendsCancelAfterInterruptedRequest() {
        long orderId = expiredOrder();
        server.changeStatus(transactionKey(orderId), "SUCCESS");
        pg.crashBeforeCancel();

        assertThatThrownBy(refundTracking::trackDueCases).isInstanceOf(TestPgFixture.InjectedCrash.class);

        assertThat(refundCase(orderId).getMoneyStatus()).isEqualTo(MoneyStatus.REFUND_REQUESTED);
        assertThat(pg.cancelCalls(providerOrderId(orderId))).isZero();

        pg.resumeCancel();
        long lookupsBefore = server.lookupCalls();
        clock.advance(Duration.ofMinutes(1));
        refundTracking.trackDueCases();

        assertThat(server.lookupCalls() - lookupsBefore).isEqualTo(1);
        assertThat(refundCase(orderId).getMoneyStatus()).isEqualTo(MoneyStatus.REFUND_REQUESTED);
        assertThat(refundCase(orderId).getCheckAttempts()).isEqualTo(1);
        assertThat(pg.cancelCalls(providerOrderId(orderId))).isZero();

        clock.set(EXPIRES_AT.plus(Duration.ofDays(2)));
        refundTracking.trackDueCases();

        RefundCase handed = refundCase(orderId);
        assertThat(handed.getMoneyStatus()).isEqualTo(MoneyStatus.REFUND_REQUESTED);
        assertThat(handed.getRefundRequestedAt()).isEqualTo(FIRST_CHECK_AT);
        assertThat(handed.getOwner()).isEqualTo(Owner.PERSON);
        assertThat(handed.getOwnerReason()).isEqualTo(RefundCase.HANDOFF_DEADLINE);
        assertThat(pg.cancelCalls(providerOrderId(orderId))).isZero();
        refundCaseMetrics.refresh();
        assertThat(gauge(CASES_METRIC, "refund_requested")).as("기한 이관 뒤에도 돈의 상태는 환불 요청함").isEqualTo(1.0);
        assertThat(gauge(PERSON_OPEN_METRIC, null)).isEqualTo(1.0);
        assertThat(gauge(OLDEST_AGE_METRIC, null)).as("요청 뒤 경과 초")
            .isEqualTo((double) Duration.between(FIRST_CHECK_AT, EXPIRES_AT.plus(Duration.ofDays(2))).getSeconds());

        lookupsBefore = server.lookupCalls();
        clock.advance(Duration.ofMinutes(30));
        refundTracking.trackDueCases();

        assertThat(server.lookupCalls() - lookupsBefore).as("사람 담당 중에도 조회는 계속").isEqualTo(1);
        assertThat(pg.cancelCalls(providerOrderId(orderId))).as("사람 담당이면 취소를 보내지 않음").isZero();
        assertThat(refundCase(orderId).isPersonOpen()).isTrue();
    }

    @Test
    @DisplayName("만료 뒤 PG가 단일 FAILED면 취소 없이 청구 없음으로 닫고 청구 없음(SYSTEM) counter가 1 는다")
    void closesFailedPaymentWithoutRefund() {
        double closedBefore = outcome("no_charge", "system");
        long orderId = expiredOrder();
        server.changeStatus(transactionKey(orderId), "FAILED");

        refundTracking.trackDueCases();

        assertThat(refundCase(orderId).getMoneyStatus()).isEqualTo(MoneyStatus.NO_CHARGE);
        assertThat(refundCase(orderId).getConcludedAt()).isEqualTo(FIRST_CHECK_AT);
        assertThat(pg.cancelCalls(providerOrderId(orderId))).isZero();
        assertThat(outcome("no_charge", "system") - closedBefore).isEqualTo(1.0);
    }

    @Test
    @DisplayName("S4: 만료 뒤 확인 중에 PG가 CANCELED면 청구 없음이 아니라 환불됨·주체 EXTERNAL로 끝나고 취소는 보내지 않는다")
    void concludesExternalRefundWhileChecking() {
        long orderId = expiredOrder();
        server.changeStatus(transactionKey(orderId), "CANCELED");

        refundTracking.trackDueCases();

        RefundCase refundCase = refundCase(orderId);
        assertThat(refundCase.getMoneyStatus()).isEqualTo(MoneyStatus.REFUNDED);
        assertThat(refundCase.getRefundActor()).isEqualTo(RefundActor.EXTERNAL);
        assertThat(refundCase.getConcludedAt()).isEqualTo(FIRST_CHECK_AT);
        assertThat(pg.cancelCalls(providerOrderId(orderId))).isZero();
    }

    @ParameterizedTest(name = "조회 장면={0}")
    @ValueSource(strings = {"pending", "zero", "lookup-error"})
    @DisplayName("S2: PENDING·0건·조회 실패가 이어지면 확인 중으로 간격을 늘리며 추적하다가 이관 기한(2일)에 조회 없이 돈은 확인 중 그대로 담당만 사람이 되고 이관 counter가 늘며 gauge는 확인 중 1·사람 담당 중 1이다")
    void assignsPersonAtDeadlineWhileInconclusive(String scene) {
        double handedBefore = assigned("handoff_deadline");
        long orderId = expiredOrder();
        switch (scene) {
            case "zero" -> server.removeTransaction(transactionKey(orderId));
            case "lookup-error" -> server.lookupErrorFor(providerOrderId(orderId));
            default -> {
            }
        }

        refundTracking.trackDueCases();

        RefundCase tracked = refundCase(orderId);
        assertThat(tracked.getMoneyStatus()).isEqualTo(MoneyStatus.CHECKING);
        assertThat(tracked.getCheckAttempts()).isEqualTo(1);
        assertThat(tracked.getNextCheckAt()).isEqualTo(FIRST_CHECK_AT.plus(Duration.ofMinutes(1)));

        Instant deadline = EXPIRES_AT.plus(Duration.ofDays(2));
        clock.set(deadline);
        long lookupsBefore = server.lookupCalls();
        refundTracking.trackDueCases();

        RefundCase handed = refundCase(orderId);
        assertThat(handed.getMoneyStatus()).isEqualTo(MoneyStatus.CHECKING);
        assertThat(handed.getOwner()).isEqualTo(Owner.PERSON);
        assertThat(handed.getOwnerReason()).isEqualTo(RefundCase.HANDOFF_DEADLINE);
        assertThat(handed.getOwnerChangedAt()).isEqualTo(deadline);
        assertThat(handed.getConcludedAt()).isNull();
        assertThat(handed.getNextCheckAt()).isEqualTo(deadline.plus(Duration.ofMinutes(30)));
        assertThat(server.lookupCalls() - lookupsBefore).isZero();
        assertThat(pg.cancelCalls(providerOrderId(orderId))).isZero();
        assertThat(assigned("handoff_deadline") - handedBefore).isEqualTo(1.0);
        refundCaseMetrics.refresh();
        assertThat(gauge(CASES_METRIC, "checking")).as("기한 이관 뒤 돈의 상태 gauge").isEqualTo(1.0);
        assertThat(gauge(PERSON_OPEN_METRIC, null)).as("기한 이관 뒤 사람 담당 중 gauge").isEqualTo(1.0);
        assertThat(gauge(OLDEST_AGE_METRIC, null)).as("환불 요청함 없음").isZero();
    }

    @ParameterizedTest(name = "PG 사실={0}")
    @ValueSource(strings = {"CANCELED", "FAILED"})
    @DisplayName("S3: 기한으로 사람 담당이 된 확인 중 대상도 30분 뒤 조회하고 PG가 CANCELED면 환불됨(EXTERNAL)·FAILED면 청구 없음으로 끝나며 담당을 SYSTEM이 자동 종료해 사람 담당 중 gauge가 0이다")
    void closesPersonOwnerByPgFact(String pgStatus) {
        long orderId = expiredOrder();
        Instant deadline = EXPIRES_AT.plus(Duration.ofDays(2));
        clock.set(deadline);
        refundTracking.trackDueCases();
        assertThat(refundCase(orderId).isPersonOpen()).isTrue();
        server.changeStatus(transactionKey(orderId), pgStatus);

        clock.advance(Duration.ofMinutes(30));
        refundTracking.trackDueCases();

        RefundCase closed = refundCase(orderId);
        Instant closedAt = deadline.plus(Duration.ofMinutes(30));
        if (pgStatus.equals("CANCELED")) {
            assertThat(closed.getMoneyStatus()).isEqualTo(MoneyStatus.REFUNDED);
            assertThat(closed.getRefundActor()).isEqualTo(RefundActor.EXTERNAL);
        } else {
            assertThat(closed.getMoneyStatus()).isEqualTo(MoneyStatus.NO_CHARGE);
            assertThat(closed.getRefundActor()).isNull();
        }
        assertThat(closed.getConcludedAt()).isEqualTo(closedAt);
        assertThat(closed.getOwner()).isEqualTo(Owner.PERSON);
        assertThat(closed.getOwnerReason()).isEqualTo(RefundCase.HANDOFF_DEADLINE);
        assertThat(closed.getOwnerClosedAt()).isEqualTo(closedAt);
        assertThat(closed.getOwnerClosedBy()).isEqualTo(RefundCase.CLOSED_BY_SYSTEM);
        assertThat(pg.cancelCalls(providerOrderId(orderId))).isZero();
        refundCaseMetrics.refresh();
        assertThat(gauge(PERSON_OPEN_METRIC, null)).as("담당이 닫힌 사람 담당은 사람 담당 중이 아님").isZero();
        assertThat(gauge(CASES_METRIC, pgStatus.equals("CANCELED") ? "refunded" : "no_charge")).isEqualTo(1.0);
    }

    @ParameterizedTest(name = "조회 장면={0}")
    @ValueSource(strings = {"amount", "order", "multiple"})
    @DisplayName("S2: 금액·주문이 다른 거래나 복수 거래가 보이면 취소하지 않고 돈은 청구 확인·담당만 즉시 사람(사유)으로 두며 이후 FAILED가 와도 담당을 유지한다")
    void assignsPersonOnMismatchImmediately(String scene) {
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
        assertThat(refundCase.getMoneyStatus()).isEqualTo(MoneyStatus.CHARGED);
        assertThat(refundCase.getOwner()).isEqualTo(Owner.PERSON);
        assertThat(refundCase.getOwnerReason()).isEqualTo(expectedReason);
        assertThat(refundCase.getOwnerChangedAt()).isEqualTo(FIRST_CHECK_AT);
        assertThat(refundCase.getConcludedAt()).isNull();
        assertThat(pg.cancelCalls(providerOrderId(orderId))).isZero();
    }

    @Test
    @DisplayName("S3: 청구 확인·사람 담당 대상에 PG가 단일 FAILED를 주면 돈·담당을 그대로 두고 취소하지 않는다")
    void keepsPersonOwnerWhenChargedCaseLooksFailed() {
        long orderId = expiredOrder();
        String key = transactionKey(orderId);
        server.replaceTransaction(key, providerOrderId(orderId), 1001, "SUCCESS");
        refundTracking.trackDueCases();
        assertThat(refundCase(orderId).getMoneyStatus()).isEqualTo(MoneyStatus.CHARGED);
        server.replaceTransaction(key, providerOrderId(orderId), 1000, "FAILED");

        clock.advance(Duration.ofMinutes(30));
        refundTracking.trackDueCases();

        RefundCase refundCase = refundCase(orderId);
        assertThat(refundCase.getMoneyStatus()).isEqualTo(MoneyStatus.CHARGED);
        assertThat(refundCase.isPersonOpen()).isTrue();
        assertThat(refundCase.getCheckAttempts()).isEqualTo(1);
        assertThat(pg.cancelCalls(providerOrderId(orderId))).isZero();
    }

    @Test
    @DisplayName("기한이 된 대상이 상한 20건보다 많으면 한 회차에 다음 조회 시각이 이른 20건만 조회하고 나머지는 손대지 않는다")
    void looksUpAtMostBatchLimitPerRun() {
        for (int index = 1; index <= 25; index++) {
            long orderId = 1000 + index;
            refundCases.save(tracking(orderId, Owner.AUTO, NOW.minusSeconds(100 - index)));
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

    @Test
    @DisplayName("S3: 실DB 대상 선택은 청구 확인·사람 담당도 고르고 상한 20건은 합계이며 자동 담당을 먼저 고른다")
    void selectsPersonCasesAfterAutoWithinBatchLimit() {
        for (int index = 1; index <= 15; index++) {
            long orderId = 1000 + index;
            refundCases.save(tracking(orderId, Owner.PERSON, NOW.minusSeconds(200 - index)));
            server.addTransaction(providerOrderId(orderId), 1000, "PENDING");
        }
        for (int index = 16; index <= 30; index++) {
            long orderId = 1000 + index;
            refundCases.save(tracking(orderId, Owner.AUTO, NOW.minusSeconds(100 - index)));
            server.addTransaction(providerOrderId(orderId), 1000, "PENDING");
        }
        long lookupsBefore = server.lookupCalls();

        refundTracking.trackDueCases();

        assertThat(server.lookupCalls() - lookupsBefore).isEqualTo(RefundTrackingService.BATCH_SIZE);
        for (int index = 16; index <= 30; index++) {
            assertThat(refundCase(1000 + index).getCheckAttempts()).as("자동 담당 %d", index).isEqualTo(1);
        }
        for (int index = 1; index <= 5; index++) {
            assertThat(refundCase(1000 + index).getCheckAttempts()).as("이른 사람 담당 %d", index).isEqualTo(1);
            assertThat(refundCase(1000 + index).getMoneyStatus()).isEqualTo(MoneyStatus.CHARGED);
        }
        for (int index = 6; index <= 15; index++) {
            assertThat(refundCase(1000 + index).getCheckAttempts()).as("상한 밖 사람 담당 %d", index).isZero();
        }
    }

    @ParameterizedTest(name = "끝난 돈의 상태={0}")
    @EnumSource(value = MoneyStatus.class, names = {"REFUNDED", "NO_CHARGE"})
    @DisplayName("돈이 끝난 대상은 다음 조회 시각·이관 기한이 지나 있어도 조회·취소하지 않고 행이 그대로다")
    void leavesConcludedCasesUntouched(MoneyStatus moneyStatus) {
        long orderId = 2001;
        clock.set(NOW.plus(Duration.ofDays(3)));
        refundCases.save(RefundCase.restore(orderId, TestPgFixture.PROVIDER_ID, providerOrderId(orderId), new Money(1000),
            moneyStatus, moneyStatus == MoneyStatus.REFUNDED ? RefundActor.SYSTEM : null, Owner.PERSON, "PRIOR", NOW, NOW,
            RefundCase.CLOSED_BY_SYSTEM, PaymentRecord.ProviderStatus.PENDING, "TR-X", NOW, NOW.plus(RefundCase.HANDOFF_AFTER),
            NOW.plus(RefundCase.PROMISE_AFTER), NOW, 3, NOW, NOW));
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

        assertThat(refundCase(orderId).getMoneyStatus()).isEqualTo(MoneyStatus.REFUND_UNKNOWN);
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
        assertThat(refundCase(orderId).getMoneyStatus()).isEqualTo(MoneyStatus.CHECKING);
        assertThat(refundCase(orderId).getNextCheckAt()).isEqualTo(FIRST_CHECK_AT);
        clock.set(FIRST_CHECK_AT);
        return orderId;
    }

    // 사람 담당은 청구 확인·기한 이관으로 열린 채로 둔다(자동 담당은 확인 중).
    private static RefundCase tracking(long orderId, Owner owner, Instant nextCheckAt) {
        boolean person = owner == Owner.PERSON;
        return RefundCase.restore(orderId, TestPgFixture.PROVIDER_ID, providerOrderId(orderId), new Money(1000),
            person ? MoneyStatus.CHARGED : MoneyStatus.CHECKING, null, owner, person ? RefundCase.HANDOFF_DEADLINE : null,
            person ? NOW : null, null, null, PaymentRecord.ProviderStatus.PENDING, null, NOW, NOW.plus(RefundCase.HANDOFF_AFTER),
            NOW.plus(RefundCase.PROMISE_AFTER), nextCheckAt, 0, null, null);
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

    private double outcome(String result, String actor) {
        Counter counter = meterRegistry.find(OUTCOME_METRIC).tag("result", result).tag("actor", actor).counter();
        return counter == null ? 0 : counter.count();
    }

    private double assigned(String reason) {
        Counter counter = meterRegistry.find(ASSIGNED_METRIC).tag("reason", reason).counter();
        return counter == null ? 0 : counter.count();
    }

    // gauge가 없으면 -1이라 값 단언이 실패한다. 돈의 상태 gauge만 money_status label로 고른다.
    private double gauge(String name, String moneyStatus) {
        Gauge gauge = moneyStatus == null ? meterRegistry.find(name).gauge()
            : meterRegistry.find(name).tag("money_status", moneyStatus).gauge();
        return gauge == null ? -1 : gauge.value();
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

        // 상한을 그대로 고르는 고정 대역이다. 다음 조회 시각을 정확히 보려고 쓴다(jitter 범위 자체는 RefundCheckJitterIntegrationTest).
        @Bean
        @Primary
        LookupJitter ceilingLookupJitter() {
            return maximum -> maximum;
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
