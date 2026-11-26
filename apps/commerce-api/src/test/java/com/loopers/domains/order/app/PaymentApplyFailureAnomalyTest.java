package com.loopers.domains.order.app;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domains.brand.app.BrandApplicationService;
import com.loopers.domains.order.app.port.PaymentApplyFailureCounter;
import com.loopers.domains.order.app.port.PaymentApplyFailureCounter.Source;
import com.loopers.domains.payment.app.port.PaymentAnomalyRepository;
import com.loopers.domains.payment.app.port.PaymentLookupScheduleRepository;
import com.loopers.domains.payment.out.PaymentAnomalyPersistenceAdapter;
import com.loopers.domains.payment.out.TestPgServer.CreateBehavior;
import com.loopers.domains.payment.out.TestPgServer;
import com.loopers.domains.point.app.PointApplicationService;
import com.loopers.domains.product.app.ProductApplicationService;
import com.loopers.domains.user.out.UserJpaEntity;
import com.loopers.domains.user.out.UserJpaRepository;
import com.loopers.support.pg.MutablePaymentClock;
import com.loopers.support.pg.TestPgFixture;
import com.loopers.utils.DatabaseCleanUp;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 결제 결과 반영 트랜잭션이 실패하면 rollback 뒤 별도 트랜잭션으로 APPLY_FAILED 이상이 commit되는지 본다(w10s7).
// 판단 근거가 로그 부재가 아니라 DB에 남는지가 핵심이다. 실패 주입은 OrderTransactionTest의 저장소 교체 설정과
// 이 클래스의 이상 기록 실패 주입을 함께 쓴다. 상태는 서비스·HTTP 호출이 끝난 뒤 트랜잭션 없는 JdbcTemplate로 다시 읽는다.
// 이 설정 조합은 다른 클래스와 공유되지 않는 컨텍스트라, 끝나면 닫아 테스트 DB 연결 상한을 넘지 않게 한다.
@DirtiesContext
@SpringBootTest
@AutoConfigureMockMvc
@Import({OrderTransactionTest.FailureInjectionConfig.class, PaymentApplyFailureAnomalyTest.AnomalyFailureConfig.class})
class PaymentApplyFailureAnomalyTest {
    private static final String METRIC_NAME = "commerce.payment.apply_failed";
    private static final List<String> SOURCES = List.of("create", "callback", "manual", "automatic");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private OrderApplicationService orderApplicationService;

    @Autowired
    private OrderTransactionTest.FailingOrderRepository failingOrderRepository;

    @Autowired
    private OrderTransactionTest.FailingPaymentRecordRepository failingPaymentRecordRepository;

    @Autowired
    private OrderTransactionTest.FailingScheduleRepository failingScheduleRepository;

    @Autowired
    private FailingAnomalyRepository failingAnomalyRepository;

    @Autowired
    private PaymentApplyFailureRecorder paymentApplyFailureRecorder;

    @Autowired
    private TransactionObservingRecorder transactionObservingRecorder;

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
    private MeterRegistry meterRegistry;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private TestPgServer server;

    private long orderId;

    private ListAppender<ILoggingEvent> recorderLogs;

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
        transactionObservingRecorder.clear();
        recorderLogs = new SnapshotAppender();
        recorderLogs.start();
        recorderLogger().addAppender(recorderLogs);
    }

    @AfterEach
    void clean() throws InterruptedException {
        recorderLogger().detachAppender(recorderLogs);
        MDC.clear();
        failingOrderRepository.disarm();
        failingPaymentRecordRepository.disarm();
        failingScheduleRepository.disarmDelete();
        failingAnomalyRepository.disarm();
        testPgFixture.close();
        jdbcTemplate.update("delete from order_items");
        databaseCleanUp.truncateAllTables();
    }

    @Test
    @DisplayName("결제 반영 실패 counter는 시작 시 source 네 값(create·callback·manual·automatic)이 모두 등록되어 있다")
    void registersEverySourceAtStartup() {
        for (String source : SOURCES) {
            assertThat(meterRegistry.find(METRIC_NAME).tag("source", source).counter())
                .as("%s{source=%s} 등록", METRIC_NAME, source).isNotNull();
        }
    }

    @Test
    @DisplayName("confirm에서 PG 성공 뒤 결과 반영이 실패하면 응답은 결제 대기 그대로이고 주문·결제는 불변이며 APPLY_FAILED(source=CREATE)가 commit되고 counter가 1 는다")
    void confirmApplyFailureCommitsAnomaly() {
        server.createBehavior(CreateBehavior.ACCEPT_SUCCESS);
        failingPaymentRecordRepository.failOnSave(2);
        double before = applyFailed("create");

        OrderResult result = orderApplicationService.confirm(1, orderId, TestPgFixture.command());

        assertThat(result.status()).isEqualTo("PAYMENT_PENDING");
        assertThat(failingPaymentRecordRepository.stateBeforeFailure()).as("실패 직전 flush된 상태").endsWith("order=CONFIRMED");
        assertThat(orderState()).containsEntry("status", "PAYMENT_PENDING").containsEntry("provider_status", "SEND_STARTED");
        assertApplyFailedAnomaly("IllegalStateException", "CREATE");
        assertRecordedOutsideTransaction();
        assertThat(applyFailed("create") - before).isEqualTo(1);
    }

    @Test
    @DisplayName("callback의 결과 반영이 실패하면 500 응답 계약은 그대로이고 주문·결제는 불변이며 APPLY_FAILED(source=CALLBACK)가 commit되고 counter가 1 는다")
    void callbackApplyFailureCommitsAnomaly() throws Exception {
        confirmPendingThenSucceedOnPg();
        Map<String, Object> before = orderState();
        double counted = applyFailed("callback");
        failingOrderRepository.failOnNextSave();

        mvc.perform(post("/api/v1/payments/callback").contentType(MediaType.APPLICATION_JSON)
                .content("{\"transactionKey\":\"" + transactionKey() + "\",\"orderId\":\"" + providerOrderId()
                    + "\",\"amount\":5000,\"status\":\"SUCCESS\"}"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.meta.result").value("FAIL"))
            .andExpect(jsonPath("$.meta.errorCode").value("Internal Server Error"))
            .andExpect(jsonPath("$.meta.message").value("일시적인 오류가 발생했습니다."));

        assertThat(failingOrderRepository.stateBeforeFailure()).as("실패 직전 flush된 상태").isNotNull();
        assertThat(orderState()).isEqualTo(before);
        assertApplyFailedAnomaly("IllegalStateException", "CALLBACK");
        assertRecordedOutsideTransaction();
        assertThat(applyFailed("callback") - counted).isEqualTo(1);
    }

    @Test
    @DisplayName("관리자 reconcile의 결과 반영이 실패하면 500 응답 계약은 그대로이고 주문·결제는 불변이며 APPLY_FAILED(source=MANUAL)가 commit되고 counter가 1 는다")
    void adminReconcileApplyFailureCommitsAnomaly() throws Exception {
        confirmPendingThenSucceedOnPg();
        Map<String, Object> before = orderState();
        double counted = applyFailed("manual");
        failingOrderRepository.failOnNextSave();

        MvcResult result = mvc.perform(post("/api-admin/v1/orders/{id}/payment/reconcile", orderId)
                .with(csrf()).with(user("admin").roles("ADMIN")))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.meta.result").value("FAIL"))
            .andExpect(jsonPath("$.meta.errorCode").value("Internal Server Error"))
            .andExpect(jsonPath("$.meta.message").value("일시적인 오류가 발생했습니다."))
            .andReturn();

        assertThat(result.getResolvedException()).isInstanceOf(IllegalStateException.class)
            .hasMessage(OrderTransactionTest.FailingOrderRepository.INJECTED_FAILURE);
        assertThat(orderState()).isEqualTo(before);
        assertApplyFailedAnomaly("IllegalStateException", "MANUAL");
        assertRecordedOutsideTransaction();
        assertThat(applyFailed("manual") - counted).isEqualTo(1);
    }

    @Test
    @DisplayName("본인 reconcile의 결과 반영이 실패하면 500 응답 계약은 그대로이고 APPLY_FAILED(source=MANUAL)가 commit된다")
    void myReconcileApplyFailureCommitsAnomaly() throws Exception {
        confirmPendingThenSucceedOnPg();
        Map<String, Object> before = orderState();
        failingOrderRepository.failOnNextSave();

        mvc.perform(post("/api/v1/orders/{id}/payment/reconcile", orderId).header("X-USER-ID", "1"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.meta.errorCode").value("Internal Server Error"));

        assertThat(orderState()).isEqualTo(before);
        assertApplyFailedAnomaly("IllegalStateException", "MANUAL");
        assertRecordedOutsideTransaction();
    }

    @Test
    @DisplayName("자동 재조회의 결과 반영이 실패하면 예외를 밖으로 내지 않고 주문·결제는 불변이며 APPLY_FAILED(source=AUTOMATIC)가 commit되고 counter가 1 는다")
    void automaticApplyFailureCommitsAnomaly() {
        confirmPendingThenSucceedOnPg();
        clock.set(paymentLookupScheduleRepository.findByOrderId(orderId).orElseThrow().nextLookupAt());
        Map<String, Object> before = orderState();
        double counted = applyFailed("automatic");
        failingOrderRepository.failOnNextSave();

        orderApplicationService.reconcileDuePayments();

        assertThat(failingOrderRepository.stateBeforeFailure()).as("실패 직전 flush된 상태").isNotNull();
        assertThat(orderState()).isEqualTo(before);
        assertApplyFailedAnomaly("IllegalStateException", "AUTOMATIC");
        assertRecordedOutsideTransaction();
        assertThat(applyFailed("automatic") - counted).isEqualTo(1);
    }

    @Test
    @DisplayName("결과 반영 실패 뒤 이상 기록까지 실패하면 수동 reconcile은 원래 예외 그대로 던지고 이상은 남지 않으며 기록 실패 ERROR 한 줄에 causeType만 남고 counter는 그대로다")
    void keepsOriginalFailureWhenRecordingFails() {
        confirmPendingThenSucceedOnPg();
        double counted = applyFailed("manual");
        failingOrderRepository.failOnNextSave();
        failingAnomalyRepository.failOnNextRecord(PaymentAnomalyRepository.Kind.APPLY_FAILED);

        assertThatThrownBy(() -> orderApplicationService.reconcilePayment(orderId))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage(OrderTransactionTest.FailingOrderRepository.INJECTED_FAILURE);

        assertThat(failingAnomalyRepository.recordFailed()).as("이상 기록 실패를 실제로 주입했는지").isTrue();
        assertThat(applyFailedRows()).isZero();
        assertThat(applyFailed("manual") - counted).isZero();
        assertSingleRecordFailureError();
    }

    @Test
    @DisplayName("confirm 결과 반영 실패 뒤 이상 기록까지 실패해도 응답은 결제 대기 그대로이고 기록 실패 ERROR 한 줄만 남는다")
    void confirmKeepsResponseWhenRecordingFails() {
        server.createBehavior(CreateBehavior.ACCEPT_SUCCESS);
        failingPaymentRecordRepository.failOnSave(2);
        failingAnomalyRepository.failOnNextRecord(PaymentAnomalyRepository.Kind.APPLY_FAILED);

        OrderResult result = orderApplicationService.confirm(1, orderId, TestPgFixture.command());

        assertThat(result.status()).isEqualTo("PAYMENT_PENDING");
        assertThat(failingAnomalyRepository.recordFailed()).as("이상 기록 실패를 실제로 주입했는지").isTrue();
        assertThat(applyFailedRows()).isZero();
        assertSingleRecordFailureError();
    }

    @Test
    @DisplayName("관리자 reconcile 결과 반영이 실패한 결제 대기 주문은 관리자 미해결 목록의 latestAnomaly에 APPLY_FAILED로 보인다")
    void unresolvedListShowsApplyFailed() throws Exception {
        confirmPendingThenSucceedOnPg();
        failingOrderRepository.failOnNextSave();
        mvc.perform(post("/api-admin/v1/orders/{id}/payment/reconcile", orderId)
                .with(csrf()).with(user("admin").roles("ADMIN")))
            .andExpect(status().isInternalServerError());

        MvcResult result = mvc.perform(get("/api-admin/v1/payments/unresolved").with(user("admin").roles("ADMIN")))
            .andExpect(status().isOk())
            .andReturn();

        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
        JsonNode row = null;
        for (JsonNode candidate : data) {
            if (candidate.get("orderId").asLong() == orderId) {
                row = candidate;
            }
        }
        assertThat(row).as("미해결 목록의 주문 %d", orderId).isNotNull();
        assertThat(row.get("latestAnomaly").isNull()).as("latestAnomaly").isFalse();
        assertThat(row.get("latestAnomaly").get("kind").asText()).isEqualTo("APPLY_FAILED");
        assertThat(row.get("latestAnomaly").get("summary").asText()).isEqualTo(summary("IllegalStateException", "MANUAL"));
    }

    // 반영 실패 기록은 주문 잠금 없이 돈다(w10s7-P3). 두 기록의 "기존 행 확인"과 "쓰기"가 겹치게 하려고 테스트 대역 저장소가
    // 기록 트랜잭션 안에서 읽기 시점을 잡은 뒤 장벽에서 서로를 기다리게 한다. 장벽은 대역에만 있고 기록기 경로에는 없다.
    @ParameterizedTest(name = "기존 APPLY_FAILED occurrences={0}")
    @ValueSource(longs = {0, 1})
    @DisplayName("같은 주문에 반영 실패 두 건을 동시에 기록하면 두 건 모두 기록되고 occurrences는 기존 값에 2를 더한 값이며 counter도 2 는다")
    void concurrentApplyFailureRecordsAreAtomic(long priorOccurrences) throws Exception {
        Source source = Source.MANUAL;
        IllegalStateException failure = new IllegalStateException("동시 반영 실패");
        for (long index = 0; index < priorOccurrences; index++) {
            paymentApplyFailureRecorder.record(orderId, source, failure);
        }
        assertThat(applyFailedOccurrences()).as("준비된 기존 occurrences").isEqualTo(priorOccurrences);
        double counted = applyFailed("manual");
        int attempts = 2;
        failingAnomalyRepository.overlapNext(attempts);
        ExecutorService executor = Executors.newFixedThreadPool(attempts);
        int thrown = 0;
        int timedOut = 0;
        try {
            var futures = new ArrayList<Future<?>>();
            for (int index = 0; index < attempts; index++) {
                futures.add(executor.submit(() -> paymentApplyFailureRecorder.record(orderId, source, failure)));
            }
            for (Future<?> future : futures) {
                try {
                    future.get(20, TimeUnit.SECONDS);
                } catch (ExecutionException e) {
                    thrown++;
                } catch (TimeoutException e) {
                    timedOut++;
                }
            }
        } finally {
            failingAnomalyRepository.disarm();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).as("executor 종료").isTrue();
        }

        long recordFailures = recorderLogs.list.stream().filter(event -> event.getLevel() == Level.ERROR).count();
        long succeeded = attempts - thrown - timedOut - recordFailures;
        assertThat(failingAnomalyRepository.overlapped()).as("두 기록이 장벽에서 실제로 겹쳤는지").isTrue();
        assertThat(succeeded + recordFailures + thrown + timedOut).as("성공+기록 실패+예외+시간 초과 합계").isEqualTo(attempts);
        assertThat(thrown).as("기록기 밖으로 나온 예외").isZero();
        assertThat(timedOut).as("시간 초과").isZero();
        assertThat(applyFailedRows()).as("APPLY_FAILED 행 수").isEqualTo(1);
        assertThat(applyFailedOccurrences()).as("occurrences = 기존 + 성공 기록 수").isEqualTo(priorOccurrences + succeeded);
        assertThat(applyFailed("manual") - counted).as("counter 증가 = 성공 기록 수").isEqualTo(succeeded);
        assertThat(recordFailures).as("기록 실패 ERROR 줄 수").isZero();
    }

    private void confirmPendingThenSucceedOnPg() {
        orderApplicationService.confirm(1, orderId, TestPgFixture.command());
        server.changeStatus(transactionKey(), "SUCCESS");
    }

    private Map<String, Object> orderState() {
        return jdbcTemplate.queryForMap("select o.status, p.result, p.provider_status, p.transaction_key from orders o"
            + " join payment_records p on p.order_id = o.id where o.id = ?", orderId);
    }

    private void assertApplyFailedAnomaly(String causeType, String source) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "select last_summary, occurrences, resolved_at from payment_anomalies where order_id = ? and kind = 'APPLY_FAILED'",
            orderId);
        assertThat(rows).as("APPLY_FAILED 이상 행").hasSize(1);
        assertThat(rows.get(0)).containsEntry("last_summary", summary(causeType, source));
        assertThat(((Number) rows.get(0).get("occurrences")).longValue()).isEqualTo(1);
        assertThat(rows.get(0).get("resolved_at")).isNull();
    }

    // 기록기 자체의 REQUIRES_NEW 안이 아니라 기록기를 부르는 순간을 본다. 그때 실제 트랜잭션이 없어야 반영 트랜잭션의
    // 주문 행 잠금을 쥔 채 기록하지 않았다고 말할 수 있다(w10s7-P3).
    private void assertRecordedOutsideTransaction() {
        assertThat(transactionObservingRecorder.transactionActiveAtCall()).as("기록기 호출 시점의 실제 트랜잭션 여부")
            .isNotEmpty()
            .containsOnly(false);
    }

    private static String summary(String causeType, String source) {
        return "causeType=" + causeType + ", source=" + source;
    }

    private long applyFailedRows() {
        return jdbcTemplate.queryForObject("select count(*) from payment_anomalies where kind = 'APPLY_FAILED'", Long.class);
    }

    private long applyFailedOccurrences() {
        List<Long> occurrences = jdbcTemplate.queryForList(
            "select occurrences from payment_anomalies where order_id = ? and kind = 'APPLY_FAILED'", Long.class, orderId);
        return occurrences.isEmpty() ? 0 : occurrences.get(0);
    }

    private void assertSingleRecordFailureError() {
        List<ILoggingEvent> errors = recorderLogs.list.stream().filter(event -> event.getLevel() == Level.ERROR).toList();
        assertThat(errors).as("이상 기록 실패 ERROR 줄 수").hasSize(1);
        ILoggingEvent error = errors.get(0);
        assertThat(error.getMDCPropertyMap()).containsEntry("orderId", String.valueOf(orderId));
        assertThat(error.getFormattedMessage())
            .contains("causeType=" + IllegalStateException.class.getSimpleName())
            .doesNotContain(FailingAnomalyRepository.INJECTED_FAILURE)
            .doesNotContain(OrderTransactionTest.FailingOrderRepository.INJECTED_FAILURE)
            .doesNotContain(OrderTransactionTest.FailingPaymentRecordRepository.INJECTED_FAILURE);
        assertThat(error.getThrowableProxy()).as("스택").isNull();
    }

    // 등록 여부는 registersEverySourceAtStartup이 본다. 경로 테스트는 증가분만 보려고 없으면 0으로 읽어 이상 행 단언까지 가게 한다.
    private double applyFailed(String source) {
        Counter counter = meterRegistry.find(METRIC_NAME).tag("source", source).counter();
        return counter == null ? 0 : counter.count();
    }

    private String transactionKey() {
        return server.transactions().get(0).transactionKey();
    }

    private String providerOrderId() {
        return String.format("LP-ORD-%010d", orderId);
    }

    private static Logger recorderLogger() {
        return (Logger) LoggerFactory.getLogger(PaymentApplyFailureRecorder.class);
    }

    @TestConfiguration
    static class AnomalyFailureConfig {
        @Bean
        @Primary
        TransactionObservingRecorder transactionObservingRecorder(PaymentAnomalyRepository paymentAnomalyRepository,
            PaymentApplyFailureCounter paymentApplyFailureCounter, PlatformTransactionManager transactionManager, Clock clock) {
            return new TransactionObservingRecorder(paymentAnomalyRepository, paymentApplyFailureCounter, transactionManager,
                clock);
        }

        @Bean
        @Primary
        FailingAnomalyRepository failingAnomalyRepository(PaymentAnomalyPersistenceAdapter delegate,
            EntityManager entityManager) {
            return new FailingAnomalyRepository(delegate, entityManager);
        }
    }

    // 기록기를 부르는 순간 호출 스레드에 실제 트랜잭션이 있었는지 남긴다. 기록 동작은 그대로 실제 기록기에 맡긴다.
    static class TransactionObservingRecorder extends PaymentApplyFailureRecorder {
        private final List<Boolean> transactionActiveAtCall = new CopyOnWriteArrayList<Boolean>();

        TransactionObservingRecorder(PaymentAnomalyRepository paymentAnomalyRepository,
            PaymentApplyFailureCounter paymentApplyFailureCounter, PlatformTransactionManager transactionManager, Clock clock) {
            super(paymentAnomalyRepository, paymentApplyFailureCounter, transactionManager, clock);
        }

        @Override
        public void record(long orderId, Source source, RuntimeException failure) {
            transactionActiveAtCall.add(TransactionSynchronizationManager.isActualTransactionActive());
            super.record(orderId, source, failure);
        }

        List<Boolean> transactionActiveAtCall() {
            return List.copyOf(transactionActiveAtCall);
        }

        void clear() {
            transactionActiveAtCall.clear();
        }
    }

    // 무장된 종류의 다음 이상 기록에서 저장 SQL을 DB로 보낸 뒤 실패한다. 기록 트랜잭션이 rollback되어 행이 남지 않는지 보려는 것이다.
    static class FailingAnomalyRepository implements PaymentAnomalyRepository {
        static final String INJECTED_FAILURE = "주입된 이상 기록 실패";

        private final PaymentAnomalyRepository delegate;
        private final EntityManager entityManager;
        private final AtomicReference<Kind> armedKind = new AtomicReference<>();
        private final AtomicReference<CyclicBarrier> overlapBarrier = new AtomicReference<>();
        private volatile boolean recordFailed;
        private volatile boolean overlapped;

        FailingAnomalyRepository(PaymentAnomalyRepository delegate, EntityManager entityManager) {
            this.delegate = delegate;
            this.entityManager = entityManager;
        }

        void failOnNextRecord(Kind kind) {
            recordFailed = false;
            armedKind.set(kind);
        }

        // 다음 기록들이 각자 트랜잭션에서 읽기 시점을 잡은 뒤 서로를 기다리게 한다. 늦게 온 기록이 먼저 commit된 기록을
        // 못 본 채 쓰는 상황을 결정적으로 만든다(REPEATABLE READ의 일관 읽기 시점은 첫 읽기에서 정해진다).
        void overlapNext(int parties) {
            overlapped = false;
            overlapBarrier.set(new CyclicBarrier(parties, () -> overlapped = true));
        }

        void disarm() {
            armedKind.set(null);
            recordFailed = false;
            CyclicBarrier barrier = overlapBarrier.getAndSet(null);
            if (barrier != null) {
                barrier.reset();
            }
        }

        boolean overlapped() {
            return overlapped;
        }

        boolean recordFailed() {
            return recordFailed;
        }

        @Override
        public void record(long orderId, Kind kind, String summary, Instant now) {
            CyclicBarrier barrier = overlapBarrier.get();
            if (barrier != null) {
                entityManager.createNativeQuery("select count(*) from payment_anomalies").getSingleResult();
                try {
                    barrier.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("겹치기 장벽 대기 중단", e);
                } catch (BrokenBarrierException | TimeoutException e) {
                    throw new IllegalStateException("겹치기 장벽 대기 실패", e);
                }
            }
            delegate.record(orderId, kind, summary, now);
            if (armedKind.compareAndSet(kind, null)) {
                entityManager.flush();
                recordFailed = true;
                throw new IllegalStateException(INJECTED_FAILURE);
            }
        }

        @Override
        public void resolveTransient(long orderId, Instant now) {
            delegate.resolveTransient(orderId, now);
        }
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
