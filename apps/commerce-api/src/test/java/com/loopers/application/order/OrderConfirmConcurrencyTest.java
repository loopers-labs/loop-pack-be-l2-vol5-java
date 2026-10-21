package com.loopers.application.order;

import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.order.OrderModel;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.product.ProductStockLedgerModel;
import com.loopers.domain.product.StockLedgerType;
import com.loopers.domain.user.UserModel;
import com.loopers.support.FakePaymentGateway;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.support.fixture.Fixtures;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.loopers.support.FakePaymentGateway.failed;
import static com.loopers.support.FakePaymentGateway.pending;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * OQ-03: 동시 확정에서 INV-03(재고 ≥ 0)이 지켜지는지, 같은 주문의 결과 반영(동기 응답·callback)과 같은 결제 시도 키의 confirm 이
 * 주문 행 락으로 직렬화되어 한 번만 반영되는지. PG 는 FakePaymentGateway (기본 SUCCESS).
 */
@SpringBootTest
class OrderConfirmConcurrencyTest {

    private static final int THREADS = 5;
    private static final String SUCCESS = "SUCCESS";
    private static final String CARD_TYPE = "SAMSUNG";
    private static final String CARD_NO = "1234-5678-9814-1451";

    @Autowired
    private OrderFacade orderFacade;
    @Autowired
    private FakePaymentGateway fakePg;
    @Autowired
    private Fixtures fixtures;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private BrandModel brand;

    @BeforeEach
    void setUp() {
        brand = fixtures.brand("브랜드");
    }

    @AfterEach
    void tearDown() {
        fakePg.reset();
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("[OQ-03][INV-03][DR-38] 재고 3개 상품을 5명이 동시에 1개씩 확정하면 3건만 성공하고 재고는 0. 원장에는 RESERVE 3건, 합 = 현재 재고.")
    @Test
    void concurrentConfirm_sameProduct_doesNotOversell() throws InterruptedException {
        ProductModel product = fixtures.product(brand.getId(), "상품", 1_000L, 3);
        List<Long> userIds = new ArrayList<>();
        List<Long> orderIds = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            UserModel user = fixtures.user();
            userIds.add(user.getId());
            orderIds.add(fixtures.draftOrder(user.getId(), product, 1).getId());
        }

        List<Object> results = runConcurrently(i -> orderFacade.confirmOrder(userIds.get(i), orderIds.get(i), "attempt-" + i, CARD_TYPE, CARD_NO));

        assertThat(results).filteredOn(SUCCESS::equals).hasSize(3);
        assertThat(results).filteredOn(r -> !SUCCESS.equals(r)).containsOnly(ErrorType.INSUFFICIENT_STOCK);
        assertThat(fixtures.stockOf(product.getId())).isZero();
        assertThat(countWithStatus(orderIds, OrderStatus.CONFIRMED)).isEqualTo(3);
        assertThat(fakePg.requests()).hasSize(3);
        List<ProductStockLedgerModel> ledger = fixtures.ledgerOf(product.getId());
        assertThat(ledger).filteredOn(e -> e.getType() == StockLedgerType.RESERVE).hasSize(3);
        assertThat(ledger.stream().mapToInt(ProductStockLedgerModel::getQuantity).sum()).isZero();
    }

    @DisplayName("[FR-ORDER-02] 같은 결제 시도 키의 confirm 5건이 동시에 와도 PG 요청·재고 예약은 한 번. 나머지는 현재 상태를 받는다.")
    @Test
    void concurrentConfirm_sameAttemptKey_callsPgOnce() throws InterruptedException {
        UserModel user = fixtures.user();
        ProductModel product = fixtures.product(brand.getId(), "상품", 1_000L, 10);
        Long orderId = fixtures.draftOrder(user.getId(), product, 2).getId();

        List<Object> results = runConcurrently(i -> orderFacade.confirmOrder(user.getId(), orderId, "attempt-1", CARD_TYPE, CARD_NO));

        assertThat(results).containsOnly(SUCCESS);
        assertThat(fakePg.requests()).hasSize(1);
        assertThat(fixtures.reloadOrder(orderId).getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(fixtures.stockOf(product.getId())).isEqualTo(8);
        assertThat(fixtures.ledgerOf(product.getId())).filteredOn(e -> e.getType() == StockLedgerType.RESERVE).hasSize(1);
    }

    @DisplayName("[FR-ORDER-02] 결제 대기 주문에 FAILED 결과 5건(동기 응답·callback 중복)이 동시에 와도 FAILED 한 번, 재고 반환도 한 번.")
    @Test
    void concurrentFailedResults_releaseOnce() throws InterruptedException {
        UserModel user = fixtures.user();
        ProductModel product = fixtures.product(brand.getId(), "상품", 1_000L, 10);
        OrderModel order = fixtures.draftOrder(user.getId(), product, 2);
        String transactionKey = FakePaymentGateway.transactionKeyOf(order.getId());
        fakePg.willRespond(pending(transactionKey));
        orderFacade.confirmOrder(user.getId(), order.getId(), "attempt-1", CARD_TYPE, CARD_NO);

        List<Object> results = runConcurrently(i -> orderFacade.applyPaymentCallback(PgOrderId.of(order.getId()), failed(transactionKey)));

        assertThat(results).containsOnly(SUCCESS);
        assertThat(fixtures.reloadOrder(order.getId()).getStatus()).isEqualTo(OrderStatus.FAILED);
        assertThat(fixtures.stockOf(product.getId())).isEqualTo(10);
        assertThat(fixtures.ledgerOf(product.getId())).filteredOn(e -> e.getType() == StockLedgerType.RELEASE).hasSize(1);
    }

    /**
     * THREADS 개 스레드가 출발 신호에 맞춰 동시에 task 를 실행한다.
     * 결과는 성공이면 SUCCESS, CoreException 이면 그 ErrorType, 그 외 예외면 예외 클래스 이름.
     */
    private List<Object> runConcurrently(IndexedTask task) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);
        Queue<Object> results = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < THREADS; i++) {
            int index = i;
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    task.run(index);
                    results.add(SUCCESS);
                } catch (CoreException e) {
                    results.add(e.getErrorType());
                } catch (Exception e) {
                    results.add(e.getClass().getSimpleName());
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();
        return List.copyOf(results);
    }

    private long countWithStatus(List<Long> orderIds, OrderStatus status) {
        return orderIds.stream()
            .map(fixtures::reloadOrder)
            .map(OrderModel::getStatus)
            .filter(status::equals)
            .count();
    }

    @FunctionalInterface
    private interface IndexedTask {
        void run(int index);
    }
}
