package com.loopers.application.payment;

import com.loopers.domain.order.OrderEventPublisher;
import com.loopers.domain.payment.ObservationSource;
import com.loopers.domain.payment.PaymentObservation;
import com.loopers.domain.payment.PgTransactionStatus;
import com.loopers.fixture.SqlCapture;
import com.loopers.testsupport.RecordingOrderEventPublisher;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.convention.TestBean;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = SqlCapture.PROPERTY)
@Import(PaymentTxTestConfig.class)
@Timeout(10)
class PaymentOverlapEvidenceIntegrationTest {

    @TestBean(name = "kafkaOrderEventPublisher", methodName = RecordingOrderEventPublisher.FACTORY, enforceOverride = true)
    private OrderEventPublisher orderEventPublisher;

    @Autowired private PaymentTransactionService tx;
    @Autowired private PaymentTxFixture fixture;
    @Autowired private ControllableProbe probe;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DatabaseCleanUp cleanup;
    private ExecutorService workers;
    private CountDownLatch release;

    @BeforeEach void prepare() { workers = Executors.newFixedThreadPool(2); release = new CountDownLatch(1); }

    @AfterEach
    void finish() throws InterruptedException {
        release.countDown();
        workers.shutdownNow();
        assertThat(workers.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        SqlCapture.stop();
        probe.clear();
        cleanup.truncateAllTables();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void bothIndependentTransactionsReachChildInsertBeforeAnyChildInsertSql(boolean conflict) throws Exception {
        var p1 = fixture.product(1000, 10);
        var p2 = fixture.product(1000, 10);
        Long a = fixture.draftOrder(1L, 1, p1);
        Long b = fixture.draftOrder(1L, 1, p2);
        var ca = PaymentTxFixture.command(PaymentTxFixture.KEY_A);
        var cb = PaymentTxFixture.command(PaymentTxFixture.KEY_B);
        if (conflict) { tx.beginConfirmation(1L, a, ca); tx.beginConfirmation(1L, b, cb); }
        var ta = conflict ? tx.prepareVerification(a, 1L, null).token() : null;
        var tb = conflict ? tx.prepareVerification(b, 1L, null).token() : null;
        String table = conflict ? "payment_conflict" : "payment";
        CountDownLatch bothReady = new CountDownLatch(2);
        Set<Long> connections = ConcurrentHashMap.newKeySet();
        Runnable barrier = () -> {
            connections.add(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
            bothReady.countDown();
            try { assertThat(release.await(5, TimeUnit.SECONDS)).isTrue(); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
        };
        var point = conflict ? PaymentTransactionProbe.Point.BEFORE_CONFLICT_INSERT : PaymentTransactionProbe.Point.BEFORE_PAYMENT_INSERT;
        String operation = conflict ? "recordConflictSummary" : "beginConfirmation";
        probe.on(point, operation, a, barrier);
        probe.on(point, operation, b, barrier);
        SqlCapture.start();
        try {
            var first = workers.submit(() -> conflict
                ? tx.applyObservation(ta, new PaymentObservation.TransactionObserved(ObservationSource.QUERY, "tx-a", external(a), 1, PgTransactionStatus.PENDING))
                : tx.beginConfirmation(1L, a, ca));
            var second = workers.submit(() -> conflict
                ? tx.applyObservation(tb, new PaymentObservation.TransactionObserved(ObservationSource.QUERY, "tx-b", external(b), 1, PgTransactionStatus.PENDING))
                : tx.beginConfirmation(1L, b, cb));
            assertThat(bothReady.await(3, TimeUnit.SECONDS)).isTrue();
            List<String> beforeInsert = SqlCapture.stop().stream().map(sql -> sql.toLowerCase(java.util.Locale.ROOT)).toList();
            assertThat(connections).hasSize(2);
            assertThat(beforeInsert).noneMatch(sql -> sql.startsWith("insert into " + table + " "));
            assertThat(beforeInsert.stream().filter(sql -> sql.startsWith("select") && sql.contains(" from " + table + " ")).count())
                .isGreaterThanOrEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class)).isZero();
            assertThat(first.isDone()).isFalse();
            assertThat(second.isDone()).isFalse();
            release.countDown();
            assertThat(first.get(3, TimeUnit.SECONDS)).isNotNull();
            assertThat(second.get(3, TimeUnit.SECONDS)).isNotNull();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class)).isEqualTo(2);
            assertThat(fixture.stock(p1.getId())).isEqualTo(9);
            assertThat(fixture.stock(p2.getId())).isEqualTo(9);
        } finally { release.countDown(); }
    }

    private static String external(Long id) { return com.loopers.domain.payment.PaymentModel.providerOrderIdOf(id); }
}
