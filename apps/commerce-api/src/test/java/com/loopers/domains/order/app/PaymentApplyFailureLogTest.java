package com.loopers.domains.order.app;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.loopers.common.error.AlreadyLogged;
import com.loopers.common.in.ApiControllerAdvice;
import com.loopers.common.in.CommerceExceptionAdvice;
import com.loopers.domains.brand.app.BrandApplicationService;
import com.loopers.domains.payment.app.port.PaymentLookupScheduleRepository;
import com.loopers.domains.payment.out.TestPgServer;
import com.loopers.domains.point.app.PointApplicationService;
import com.loopers.domains.product.app.ProductApplicationService;
import com.loopers.domains.user.out.UserJpaEntity;
import com.loopers.domains.user.out.UserJpaRepository;
import com.loopers.support.pg.MutablePaymentClock;
import com.loopers.support.pg.TestPgFixture;
import com.loopers.utils.DatabaseCleanUp;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 수동 reconcile·callback의 결과 반영 트랜잭션이 실패하면 주문 열쇠가 붙은 ERROR 한 줄만 남고 응답 계약은 그대로인지 본다.
// 실패 주입은 OrderTransactionTest의 저장소 교체 설정을 그대로 써서 같은 컨텍스트를 재사용한다.
@SpringBootTest
@AutoConfigureMockMvc
@Import(OrderTransactionTest.FailureInjectionConfig.class)
class PaymentApplyFailureLogTest {
    private static final List<String> LOGGERS = List.of(OrderApplicationService.class.getName(),
        ApiControllerAdvice.class.getName(), CommerceExceptionAdvice.class.getName());

    @Autowired
    private MockMvc mvc;

    @Autowired
    private OrderApplicationService orderApplicationService;

    @Autowired
    private OrderTransactionTest.FailingOrderRepository failingOrderRepository;

    @Autowired
    private OrderTransactionTest.FailingScheduleRepository failingScheduleRepository;

    @Autowired
    private OrderTransactionTest.FailingPaymentRecordRepository failingPaymentRecordRepository;

    @Autowired
    private PaymentLookupScheduleRepository paymentLookupScheduleRepository;

    @Autowired
    private MutablePaymentClock clock;

    @Autowired
    private BrandApplicationService brandApplicationService;

    @Autowired
    private ProductApplicationService productApplicationService;

    @Autowired
    private PointApplicationService pointApplicationService;

    @Autowired
    private UserJpaRepository userJpaRepository;

    @Autowired
    private TestPgFixture testPgFixture;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private TestPgServer server;

    private long orderId;

    private SnapshotAppender logs;

    @BeforeEach
    void prepare() {
        MDC.clear();
        clock.set(Instant.parse("2026-10-21T00:00:00Z"));
        server = testPgFixture.start();
        userJpaRepository.save(new UserJpaEntity(1));
        long brandId = brandApplicationService.create("브랜드").id();
        long firstProductId = productApplicationService.create(brandId, "첫 상품", 2000, 5).id();
        long secondProductId = productApplicationService.create(brandId, "둘째 상품", 3000, 5).id();
        pointApplicationService.charge(1, 10000);
        orderId = orderApplicationService.create(1, List.of(
            new OrderApplicationService.ItemRequest(firstProductId, 1),
            new OrderApplicationService.ItemRequest(secondProductId, 1))).id();
        orderApplicationService.confirm(1, orderId, TestPgFixture.command());
        server.changeStatus(transactionKey(), "SUCCESS");
        logs = new SnapshotAppender();
        logs.start();
        for (String name : LOGGERS) {
            logger(name).addAppender(logs);
        }
    }

    @AfterEach
    void clean() throws InterruptedException {
        for (String name : LOGGERS) {
            logger(name).detachAppender(logs);
        }
        MDC.clear();
        failingOrderRepository.disarm();
        failingPaymentRecordRepository.disarm();
        failingScheduleRepository.disarmDelete();
        testPgFixture.close();
        jdbcTemplate.update("delete from order_items");
        databaseCleanUp.truncateAllTables();
    }

    @Test
    @DisplayName("관리자 reconcile의 결과 반영이 실패하면 500 응답·본문은 그대로이고 ERROR는 주문 열쇠·causeType이 붙고 원문·스택이 없는 한 줄만 남는다")
    void adminReconcileApplyFailureLogsOnceWithOrderKey() throws Exception {
        failingOrderRepository.failOnNextSave();

        MvcResult result = mvc.perform(post("/api-admin/v1/orders/{id}/payment/reconcile", orderId)
                .with(csrf()).with(user("admin").roles("ADMIN")))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.meta.result").value("FAIL"))
            .andExpect(jsonPath("$.meta.errorCode").value("Internal Server Error"))
            .andExpect(jsonPath("$.meta.message").value("일시적인 오류가 발생했습니다."))
            .andReturn();

        assertInjectedFailureResolved(result);
        assertSingleOrderError("MANUAL", result.getResolvedException());
    }

    @Test
    @DisplayName("본인 reconcile의 결과 반영이 실패하면 500 응답·본문은 그대로이고 ERROR는 주문 열쇠·causeType이 붙고 원문·스택이 없는 한 줄만 남는다")
    void myReconcileApplyFailureLogsOnceWithOrderKey() throws Exception {
        failingOrderRepository.failOnNextSave();

        MvcResult result = mvc.perform(post("/api/v1/orders/{id}/payment/reconcile", orderId).header("X-USER-ID", "1"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.meta.result").value("FAIL"))
            .andExpect(jsonPath("$.meta.errorCode").value("Internal Server Error"))
            .andExpect(jsonPath("$.meta.message").value("일시적인 오류가 발생했습니다."))
            .andReturn();

        assertInjectedFailureResolved(result);
        assertSingleOrderError("MANUAL", result.getResolvedException());
    }

    @Test
    @DisplayName("callback의 결과 반영이 실패하면 500 응답·본문은 그대로이고 ERROR는 주문 열쇠·causeType이 붙고 원문·스택이 없는 한 줄만 남는다")
    void callbackApplyFailureLogsOnceWithOrderKey() throws Exception {
        failingOrderRepository.failOnNextSave();

        MvcResult result = mvc.perform(post("/api/v1/payments/callback").contentType(MediaType.APPLICATION_JSON)
                .content("{\"transactionKey\":\"" + transactionKey() + "\",\"orderId\":\"" + providerOrderId()
                    + "\",\"amount\":5000,\"status\":\"SUCCESS\"}"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.meta.result").value("FAIL"))
            .andExpect(jsonPath("$.meta.errorCode").value("Internal Server Error"))
            .andExpect(jsonPath("$.meta.message").value("일시적인 오류가 발생했습니다."))
            .andReturn();

        assertInjectedFailureResolved(result);
        assertSingleOrderError("CALLBACK", result.getResolvedException());
    }

    @Test
    @DisplayName("관리자 reconcile의 결과 반영이 주문 행 잠금 대기 초과로 실패하면 503 응답·본문은 그대로이고 ERROR 한 줄만 남으며 advice 잠금 경고는 없다")
    void adminReconcileLockFailureLogsOnceWithOrderKey() throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        MvcResult result;
        try {
            Future<?> holder = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                jdbcTemplate.queryForObject("select id from orders where id = ? for update", Long.class, orderId);
                locked.countDown();
                awaitRelease(release);
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

            result = mvc.perform(post("/api-admin/v1/orders/{id}/payment/reconcile", orderId)
                    .with(csrf()).with(user("admin").roles("ADMIN")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.meta.result").value("FAIL"))
                .andExpect(jsonPath("$.meta.errorCode").value("Service Unavailable"))
                .andExpect(jsonPath("$.meta.message").value("잠시 후 다시 시도해 주세요."))
                .andReturn();

            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }

        assertThat(result.getResolvedException()).isInstanceOf(PessimisticLockingFailureException.class);
        assertSingleOrderError("MANUAL", result.getResolvedException());
        assertThat(logs.list).filteredOn(event -> event.getLoggerName().equals(CommerceExceptionAdvice.class.getName()))
            .isEmpty();
    }

    @Test
    @DisplayName("수동 reconcile의 결과 반영이 실패하면 서비스는 같은 타입·메시지의 예외를 다시 던지고 이미 기록했다는 표식을 붙인다")
    void rethrowsSameFailureWithAlreadyLoggedMark() {
        failingOrderRepository.failOnNextSave();

        assertThatThrownBy(() -> orderApplicationService.reconcilePayment(orderId))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage(OrderTransactionTest.FailingOrderRepository.INJECTED_FAILURE)
            .satisfies(thrown -> assertThat(thrown.getSuppressed()).hasAtLeastOneElementOfType(AlreadyLogged.class));
    }

    @Test
    @DisplayName("자동 재조회의 결과 반영이 실패하면 기존 건별 ERROR 한 줄만 남고 새 줄이 더해지지 않는다")
    void automaticReconcileApplyFailureKeepsSingleExistingError() {
        clock.set(paymentLookupScheduleRepository.findByOrderId(orderId).orElseThrow().nextLookupAt());
        failingOrderRepository.failOnNextSave();

        orderApplicationService.reconcileDuePayments();

        List<ILoggingEvent> errors = errors();
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).getFormattedMessage()).contains("주문 자동 재조회 처리 실패");
        assertThat(errors.get(0).getMDCPropertyMap()).containsEntry("orderId", String.valueOf(orderId));
    }

    @Test
    @DisplayName("결과 반영 전에 끝나는 수동 reconcile 오류(존재하지 않는 주문)는 404 그대로이고 서비스 ERROR를 남기지 않는다")
    void failureBeforeApplyIsNotLoggedAsApplyFailure() throws Exception {
        mvc.perform(post("/api-admin/v1/orders/{id}/payment/reconcile", orderId + 1000)
                .with(csrf()).with(user("admin").roles("ADMIN")))
            .andExpect(status().isNotFound());

        assertThat(errors()).isEmpty();
    }

    private void assertInjectedFailureResolved(MvcResult result) {
        assertThat(result.getResolvedException())
            .isInstanceOf(IllegalStateException.class)
            .hasMessage(OrderTransactionTest.FailingOrderRepository.INJECTED_FAILURE);
    }

    private void assertSingleOrderError(String source, Exception resolved) {
        List<ILoggingEvent> errors = errors();
        assertThat(errors).as("같은 사건의 ERROR 줄 수").hasSize(1);
        ILoggingEvent error = errors.get(0);
        assertThat(error.getLoggerName()).isEqualTo(OrderApplicationService.class.getName());
        assertThat(error.getMDCPropertyMap()).containsEntry("orderId", String.valueOf(orderId));
        assertThat(error.getFormattedMessage())
            .contains("source=" + source)
            .contains("causeType=" + resolved.getClass().getSimpleName())
            .doesNotContain(OrderTransactionTest.FailingOrderRepository.INJECTED_FAILURE);
        assertThat(error.getThrowableProxy()).as("스택").isNull();
        assertThat(logs.list).allSatisfy(event -> {
            assertThat(event.getFormattedMessage()).doesNotContain(TestPgFixture.CARD_NO);
            assertThat(event.getMDCPropertyMap().values()).noneMatch(value -> value.contains(TestPgFixture.CARD_NO));
        });
    }

    private List<ILoggingEvent> errors() {
        return logs.list.stream().filter(event -> event.getLevel() == Level.ERROR).toList();
    }

    private String transactionKey() {
        return server.transactions().get(0).transactionKey();
    }

    private String providerOrderId() {
        return String.format("LP-ORD-%010d", orderId);
    }

    private static void awaitRelease(CountDownLatch release) {
        try {
            if (!release.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("잠금 해제 신호를 받지 못했습니다.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
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
