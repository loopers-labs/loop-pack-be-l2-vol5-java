package com.loopers.domains.order.app;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.loopers.domains.brand.app.BrandApplicationService;
import com.loopers.domains.order.app.OrderTransactionTest.FailingPaymentRecordRepository;
import com.loopers.domains.payment.app.port.PaymentLookupScheduleRepository;
import com.loopers.domains.payment.out.TestPgPaymentGateway;
import com.loopers.domains.payment.out.TestPgServer.CreateBehavior;
import com.loopers.domains.payment.out.TestPgServer.LookupBehavior;
import com.loopers.domains.payment.out.TestPgServer;
import com.loopers.domains.product.app.ProductApplicationService;
import com.loopers.domains.user.out.UserJpaEntity;
import com.loopers.domains.user.out.UserJpaRepository;
import com.loopers.support.pg.MutablePaymentClock;
import com.loopers.support.pg.TestPgFixture;
import com.loopers.utils.DatabaseCleanUp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

// 경계 로그는 문구 전체가 아니라 logger·레벨·MDC·인자로 본다. 이벤트는 아래 메시지 일부로 고르며, 이 조각이 문구 계약의 전부다.
// 반영 저장 실패 주입은 OrderTransactionTest의 테스트 설정을 그대로 쓴다(같은 컨텍스트 재사용).
@SpringBootTest
@AutoConfigureMockMvc
@Import(OrderTransactionTest.FailureInjectionConfig.class)
class PaymentBoundaryLogIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-21T00:00:00Z");
    private static final String SERVICE_LOGGER = OrderApplicationService.class.getName();
    private static final String ADAPTER_LOGGER = TestPgPaymentGateway.class.getName();
    private static final String STARTED = "결제를 시작";
    private static final String CREATE_RESULT = "결제 생성";
    private static final String CONFIRMED = "결제를 확정";
    private static final String FAILED_CLOSED = "결제 실패로 종결";
    private static final String LOOKUP_JUDGMENT = "조회 판정";

    @Autowired private BrandApplicationService brands;
    @Autowired private ProductApplicationService products;
    @Autowired private OrderApplicationService orders;
    @Autowired private MutablePaymentClock clock;
    @Autowired private TestPgFixture pg;
    @Autowired private FailingPaymentRecordRepository failingPaymentRecordRepository;
    @Autowired private PaymentLookupScheduleRepository schedules;
    @Autowired private UserJpaRepository users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DatabaseCleanUp cleanup;
    private TestPgServer server;
    private long orderId;
    private SnapshotAppender logs;
    private Level serviceLevel;
    private Level adapterLevel;

    @BeforeEach
    void prepare() {
        MDC.clear();
        clock.set(NOW);
        server = pg.start();
        users.save(new UserJpaEntity(1));
        long brand = brands.create("브랜드").id();
        long productId = products.create(brand, "상품", 1000, 5).id();
        orderId = orders.create(1, List.of(new OrderApplicationService.ItemRequest(productId, 2))).id();
        logs = new SnapshotAppender();
        logs.start();
        serviceLevel = logger(SERVICE_LOGGER).getLevel();
        adapterLevel = logger(ADAPTER_LOGGER).getLevel();
        logger(SERVICE_LOGGER).setLevel(Level.DEBUG);
        logger(ADAPTER_LOGGER).setLevel(Level.DEBUG);
        logger(SERVICE_LOGGER).addAppender(logs);
        logger(ADAPTER_LOGGER).addAppender(logs);
    }

    @AfterEach
    void clean() throws Exception {
        logger(SERVICE_LOGGER).detachAppender(logs);
        logger(ADAPTER_LOGGER).detachAppender(logs);
        logger(SERVICE_LOGGER).setLevel(serviceLevel);
        logger(ADAPTER_LOGGER).setLevel(adapterLevel);
        MDC.clear();
        failingPaymentRecordRepository.disarm();
        pg.close();
        jdbc.update("delete from order_items");
        cleanup.truncateAllTables();
    }

    @Test
    @DisplayName("정상 확정 confirm은 시작·생성 결과·확정 INFO가 한 줄씩만 남고 WARN 이상은 없으며 거래 키는 확정 줄 MDC에만 DB 값으로 붙는다")
    void confirmedPaymentLeavesStartCreateAndConfirmInfo() {
        String attemptKey = UUID.randomUUID().toString();
        server.createBehavior(CreateBehavior.ACCEPT_SUCCESS);

        orders.confirm(1, orderId, TestPgFixture.command(attemptKey));

        assertThat(atLeast(Level.INFO)).allSatisfy(event -> assertThat(event.getLevel()).isEqualTo(Level.INFO));
        assertThat(atLeast(Level.INFO)).hasSize(3);
        ILoggingEvent started = single(SERVICE_LOGGER, STARTED);
        ILoggingEvent created = single(SERVICE_LOGGER, CREATE_RESULT);
        ILoggingEvent confirmed = single(SERVICE_LOGGER, CONFIRMED);
        assertThat(started.getMDCPropertyMap())
            .containsEntry("orderId", String.valueOf(orderId))
            .containsEntry("attemptKey", attemptKey)
            .containsEntry("providerOrderId", providerOrderId())
            .doesNotContainKey("transactionKey");
        assertThat(arguments(created)).contains("Accepted");
        assertThat(confirmed.getMDCPropertyMap())
            .containsEntry("orderId", String.valueOf(orderId))
            .containsEntry("transactionKey", storedTransactionKey());
        assertThat(paymentState()).containsEntry("status", "CONFIRMED");
        assertNoCardNumber();
    }

    @Test
    @DisplayName("생성 응답이 유실되면 INFO 이상에는 서비스의 생성 결과 모름 WARN 한 줄(원인에 timeout 예외명)만 남고 확정·실패 줄은 없으며 주문은 결제 대기·UNKNOWN이다")
    void lostCreateResponseLeavesSingleUnknownWarn() {
        String attemptKey = UUID.randomUUID().toString();
        pg.loseResponseAfterSuccess(providerOrderId());

        orders.confirm(1, orderId, TestPgFixture.command(attemptKey));

        List<ILoggingEvent> warnings = atLeast(Level.WARN);
        assertThat(warnings).hasSize(1);
        ILoggingEvent unknown = warnings.get(0);
        assertThat(unknown.getLevel()).isEqualTo(Level.WARN);
        assertThat(unknown.getLoggerName()).isEqualTo(SERVICE_LOGGER);
        assertThat(unknown.getFormattedMessage()).contains(CREATE_RESULT);
        assertThat(arguments(unknown)).contains("Unknown");
        assertThat(arguments(unknown)).anySatisfy(argument -> assertThat(argument).contains("SocketTimeoutException"));
        assertThat(unknown.getMDCPropertyMap())
            .containsEntry("orderId", String.valueOf(orderId))
            .containsEntry("attemptKey", attemptKey)
            .containsEntry("providerOrderId", providerOrderId())
            .doesNotContainKey("transactionKey");
        assertThat(events(SERVICE_LOGGER, CONFIRMED)).isEmpty();
        assertThat(events(SERVICE_LOGGER, FAILED_CLOSED)).isEmpty();
        assertThat(paymentState()).containsEntry("status", "PAYMENT_PENDING").containsEntry("provider_status", "UNKNOWN");
        assertNoCardNumber();
    }

    @Test
    @DisplayName("PG 성공 응답 뒤 결과 반영 트랜잭션이 실패하면 ERROR 한 줄에 예외 종류 이름이 남고 예외 원문·스택은 남지 않으며 주문은 결제 대기다")
    void resultSaveFailureAfterCreateLeavesErrorWithCauseType() {
        server.createBehavior(CreateBehavior.ACCEPT_SUCCESS);
        failingPaymentRecordRepository.failOnSave(2);

        orders.confirm(1, orderId, TestPgFixture.command());

        List<ILoggingEvent> errors = atLeast(Level.ERROR);
        assertThat(errors).hasSize(1);
        ILoggingEvent error = errors.get(0);
        assertThat(error.getLoggerName()).isEqualTo(SERVICE_LOGGER);
        assertThat(arguments(error)).contains(IllegalStateException.class.getSimpleName());
        assertThat(error.getFormattedMessage()).doesNotContain(FailingPaymentRecordRepository.INJECTED_FAILURE);
        assertThat(error.getThrowableProxy()).isNull();
        assertThat(error.getMDCPropertyMap())
            .containsEntry("orderId", String.valueOf(orderId))
            .containsEntry("transactionKey", server.transactions().get(0).transactionKey());
        assertThat(paymentState()).containsEntry("status", "PAYMENT_PENDING");
        assertNoCardNumber();
    }

    @Test
    @DisplayName("자동 재조회가 PG SUCCESS를 확인한 뒤 반영 트랜잭션이 실패하면 SUCCESS 조회 판정 INFO 한 줄과 예외 종류가 담긴 ERROR 한 줄이 남고 주문은 결제 대기다")
    void resultSaveFailureAfterLookupLeavesVerifiedInfoAndError() {
        pg.loseResponseAfterSuccess(providerOrderId());
        orders.confirm(1, orderId, TestPgFixture.command());
        clock.set(schedules.findByOrderId(orderId).orElseThrow().nextLookupAt());
        failingPaymentRecordRepository.failOnSave(1);
        logs.list.clear();

        orders.reconcileDuePayments();

        ILoggingEvent verified = single(SERVICE_LOGGER, LOOKUP_JUDGMENT);
        assertThat(verified.getLevel()).isEqualTo(Level.INFO);
        assertThat(arguments(verified)).contains("SUCCESS");
        assertThat(verified.getMDCPropertyMap())
            .containsEntry("orderId", String.valueOf(orderId))
            .containsEntry("transactionKey", server.transactions().get(0).transactionKey());
        List<ILoggingEvent> errors = atLeast(Level.ERROR);
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).getLoggerName()).isEqualTo(SERVICE_LOGGER);
        assertThat(arguments(errors.get(0))).contains(IllegalStateException.class.getSimpleName());
        assertThat(errors.get(0).getMDCPropertyMap()).containsEntry("orderId", String.valueOf(orderId));
        assertThat(paymentState()).containsEntry("status", "PAYMENT_PENDING");
        assertNoCardNumber();
    }

    @Test
    @DisplayName("PG 조회가 실패하면 서비스의 조회 판정 WARN 한 줄에 LOOKUP_FAILED와 원인이 남고 어댑터 WARN은 없다")
    void lookupFailureLeavesSingleUncertainWarn() {
        orders.confirm(1, orderId, TestPgFixture.command());
        server.lookupBehavior(LookupBehavior.ERROR_500);
        logs.list.clear();

        orders.reconcilePayment(orderId);

        List<ILoggingEvent> warnings = atLeast(Level.WARN);
        assertThat(warnings).hasSize(1);
        ILoggingEvent uncertain = warnings.get(0);
        assertThat(uncertain.getLevel()).isEqualTo(Level.WARN);
        assertThat(uncertain.getLoggerName()).isEqualTo(SERVICE_LOGGER);
        assertThat(uncertain.getFormattedMessage()).contains(LOOKUP_JUDGMENT);
        assertThat(arguments(uncertain)).contains("LOOKUP_FAILED");
        assertThat(arguments(uncertain)).anySatisfy(argument -> assertThat(argument).contains("HTTP 500"));
        assertThat(uncertain.getMDCPropertyMap())
            .containsEntry("orderId", String.valueOf(orderId))
            .containsEntry("providerOrderId", providerOrderId());
        assertNoCardNumber();
    }

    @Test
    @DisplayName("PG가 FAILED로 확정한 결제는 실패 종결 INFO 한 줄에 FAILED가 남고 WARN 이상은 없다")
    void failedPaymentLeavesFailedInfo() {
        server.createBehavior(CreateBehavior.ACCEPT_FAILED);

        orders.confirm(1, orderId, TestPgFixture.command());

        ILoggingEvent failed = single(SERVICE_LOGGER, FAILED_CLOSED);
        assertThat(failed.getLevel()).isEqualTo(Level.INFO);
        assertThat(arguments(failed)).contains("FAILED");
        assertThat(failed.getMDCPropertyMap())
            .containsEntry("orderId", String.valueOf(orderId))
            .containsEntry("transactionKey", storedTransactionKey());
        assertThat(atLeast(Level.WARN)).isEmpty();
        assertThat(paymentState()).containsEntry("status", "PAYMENT_FAILED");
        assertNoCardNumber();
    }

    private List<ILoggingEvent> atLeast(Level level) {
        return logs.list.stream()
            .filter(event -> event.getLevel().isGreaterOrEqual(level))
            .toList();
    }

    private List<ILoggingEvent> events(String loggerName, String fragment) {
        return logs.list.stream()
            .filter(event -> event.getLoggerName().equals(loggerName))
            .filter(event -> event.getLevel().isGreaterOrEqual(Level.INFO))
            .filter(event -> event.getFormattedMessage().contains(fragment))
            .toList();
    }

    private ILoggingEvent single(String loggerName, String fragment) {
        List<ILoggingEvent> found = events(loggerName, fragment);
        assertThat(found).as(fragment).hasSize(1);
        return found.get(0);
    }

    private static List<String> arguments(ILoggingEvent event) {
        Object[] arguments = event.getArgumentArray();
        if (arguments == null) {
            return List.of();
        }
        return Arrays.stream(arguments).map(String::valueOf).toList();
    }

    private Map<String, Object> paymentState() {
        return jdbc.queryForMap("select o.status, p.provider_status from orders o join payment_records p on p.order_id = o.id"
            + " where o.id = ?", orderId);
    }

    private String storedTransactionKey() {
        return jdbc.queryForObject("select transaction_key from payment_records where order_id = ?", String.class, orderId);
    }

    private void assertNoCardNumber() {
        assertThat(logs.list).allSatisfy(event -> {
            assertThat(event.getFormattedMessage()).doesNotContain(TestPgFixture.CARD_NO);
            assertThat(event.getMDCPropertyMap().values()).noneMatch(value -> value.contains(TestPgFixture.CARD_NO));
            assertThat(arguments(event)).noneMatch(argument -> argument.contains(TestPgFixture.CARD_NO));
        });
    }

    private String providerOrderId() {
        return String.format("LP-ORD-%010d", orderId);
    }

    private static Logger logger(String name) {
        return (Logger) LoggerFactory.getLogger(name);
    }

    // 이벤트의 MDC는 처음 읽을 때 현재 스레드에서 가져오므로, 기록 시점의 값을 남기려고 받는 즉시 고정한다.
    private static final class SnapshotAppender extends ListAppender<ILoggingEvent> {
        @Override
        protected void append(ILoggingEvent event) {
            event.prepareForDeferredProcessing();
            super.append(event);
        }
    }
}
