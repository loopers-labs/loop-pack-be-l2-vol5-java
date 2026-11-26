package com.loopers.domains.order.app;

import com.loopers.domains.brand.app.BrandApplicationService;
import com.loopers.domains.order.app.OrderTransactionTest.FailingPaymentRecordRepository;
import com.loopers.domains.order.app.ReconcileBatchResult.Item;
import com.loopers.domains.order.app.ReconcileBatchResult.Outcome;
import com.loopers.domains.order.app.port.ReconcilePacer;
import com.loopers.domains.payment.out.TestPgServer;
import com.loopers.domains.product.app.ProductApplicationService;
import com.loopers.domains.user.out.UserJpaEntity;
import com.loopers.domains.user.out.UserJpaRepository;
import com.loopers.support.pg.TestPgFixture;
import com.loopers.utils.DatabaseCleanUp;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

// 간격 대기는 기록용 pacer로 바꿔 "언제 기다렸는가"를 PG 조회 수로 본다. 반영 실패 주입은 OrderTransactionTest의 테스트 설정을 쓴다.
@SpringBootTest
@Import({OrderTransactionTest.FailureInjectionConfig.class, PaymentReconcileBatchPacingIntegrationTest.PacerConfig.class})
class PaymentReconcileBatchPacingIntegrationTest {
    private static final long MISSING_ORDER_ID = 999_999L;
    @Autowired private PaymentReconcileBatchService batchService;
    @Autowired private ReconcileBatchPolicy reconcileBatchPolicy;
    @Autowired private RecordingReconcilePacer pacer;
    @Autowired private FailingPaymentRecordRepository failingPaymentRecordRepository;
    @Autowired private BrandApplicationService brands;
    @Autowired private ProductApplicationService products;
    @Autowired private OrderApplicationService orders;
    @Autowired private UserJpaRepository users;
    @Autowired private TestPgFixture pg;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DatabaseCleanUp cleanup;
    private TestPgServer server;
    private long productId;

    @BeforeEach
    void prepare() {
        server = pg.start();
        pacer.reset(server::lookupCalls);
        users.save(new UserJpaEntity(1));
        long brandId = brands.create("브랜드").id();
        productId = products.create(brandId, "상품", 1000, 10).id();
        // 주입 저장소 설정을 공유하는 다른 테스트와 같이 상품 두 개를 둔다.
        products.create(brandId, "둘째 상품", 1000, 10);
    }

    @AfterEach
    void clean() throws Exception {
        failingPaymentRecordRepository.disarm();
        pg.close();
        jdbc.update("delete from order_items");
        cleanup.truncateAllTables();
    }

    @Test
    @DisplayName("PG 조회가 3건이면 조회 사이에만 2번 기다리고 대상 아님·이미 종결 앞이나 첫 조회 앞에서는 기다리지 않는다")
    void pausesOnlyBetweenLookups() {
        long closed = closedOrder();
        long laterClosed = closedOrder();
        long firstLookup = pendingOrder();
        server.changeStatus(transactionKey(firstLookup), "SUCCESS");
        long draft = orders.create(1, List.of(new OrderApplicationService.ItemRequest(productId, 1))).id();
        long secondLookup = pendingOrder();
        long thirdLookup = pendingOrder();
        server.changeStatus(transactionKey(thirdLookup), "SUCCESS");
        long lookups = server.lookupCalls();

        ReconcileBatchResult result = batchService.reconcile("admin",
            List.of(MISSING_ORDER_ID, closed, firstLookup, draft, laterClosed, secondLookup, MISSING_ORDER_ID + 1, thirdLookup));

        assertThat(result.results()).extracting(Item::outcome).containsExactly(Outcome.NOT_APPLICABLE,
            Outcome.ALREADY_CLOSED, Outcome.RESOLVED, Outcome.NOT_APPLICABLE, Outcome.ALREADY_CLOSED, Outcome.STOPPED,
            Outcome.NOT_APPLICABLE, Outcome.RESOLVED);
        assertThat(server.lookupCalls() - lookups).isEqualTo(3);
        assertThat(pacer.lookupsAtPause()).as("기다린 시점의 누적 PG 조회 수").containsExactly(lookups + 1, lookups + 2);
        assertThat(pacer.intervals()).containsOnly(reconcileBatchPolicy.interval());
    }

    @Test
    @DisplayName("한 주문의 반영이 실패하면 그 주문만 멈춤(PROCESSING_FAILED)으로 rollback되고 다음 주문은 계속 확정한다")
    void continuesAfterOneOrderFailsToApply() {
        long failing = pendingOrder();
        server.changeStatus(transactionKey(failing), "SUCCESS");
        long next = pendingOrder();
        server.changeStatus(transactionKey(next), "SUCCESS");
        // 확정 반영에서 결제 기록 저장은 1번뿐이라 첫 주문의 반영만 실패한다.
        failingPaymentRecordRepository.failOnSave(1);

        ReconcileBatchResult result = batchService.reconcile("admin", List.of(failing, next));

        assertThat(failingPaymentRecordRepository.stateBeforeFailure()).as("실패 직전 DB로 보낸 상태")
            .endsWith("order=CONFIRMED");
        assertThat(result.stopReason()).isNull();
        assertThat(result.processed()).isEqualTo(2);
        Item failed = result.results().get(0);
        assertThat(failed.outcome()).isEqualTo(Outcome.STOPPED);
        assertThat(failed.reason()).isEqualTo("PROCESSING_FAILED");
        assertThat(failed.afterStatus()).isEqualTo("PAYMENT_PENDING");
        assertThat(result.results().get(1).outcome()).isEqualTo(Outcome.RESOLVED);
        assertThat(orderState(failing)).as("rollback 후 새 조회").isEqualTo("PAYMENT_PENDING/PENDING");
        assertThat(orderState(next)).isEqualTo("CONFIRMED/SUCCESS");
    }

    private long pendingOrder() {
        long orderId = orders.create(1, List.of(new OrderApplicationService.ItemRequest(productId, 1))).id();
        orders.confirm(1, orderId, TestPgFixture.command());
        return orderId;
    }

    private long closedOrder() {
        long orderId = pendingOrder();
        server.changeStatus(transactionKey(orderId), "SUCCESS");
        orders.reconcilePayment(orderId);
        return orderId;
    }

    private String orderState(long orderId) {
        return jdbc.queryForObject("select concat(o.status, '/', p.provider_status) from orders o"
            + " join payment_records p on p.order_id = o.id where o.id = ?", String.class, orderId);
    }

    private String transactionKey(long orderId) {
        String providerOrderId = String.format("LP-ORD-%010d", orderId);
        return server.transactions().stream()
            .filter(transaction -> transaction.orderId().equals(providerOrderId))
            .findFirst().orElseThrow().transactionKey();
    }

    @TestConfiguration
    static class PacerConfig {
        @Bean
        @Primary
        RecordingReconcilePacer recordingReconcilePacer() {
            return new RecordingReconcilePacer();
        }
    }

    static class RecordingReconcilePacer implements ReconcilePacer {
        private final List<Long> lookupsAtPause = new CopyOnWriteArrayList<>();
        private final List<Duration> intervals = new CopyOnWriteArrayList<>();
        private volatile LongSupplier lookupCounter = () -> 0L;

        void reset(LongSupplier lookupCounter) {
            this.lookupCounter = lookupCounter;
            lookupsAtPause.clear();
            intervals.clear();
        }

        List<Long> lookupsAtPause() {
            return lookupsAtPause;
        }

        List<Duration> intervals() {
            return intervals;
        }

        @Override
        public void pause(Duration interval) {
            lookupsAtPause.add(lookupCounter.getAsLong());
            intervals.add(interval);
        }
    }
}
