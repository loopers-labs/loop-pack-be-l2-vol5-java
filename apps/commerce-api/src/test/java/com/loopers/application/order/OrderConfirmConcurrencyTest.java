package com.loopers.application.order;

import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.order.OrderModel;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.user.UserModel;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OQ-03: 동시 확정에서 INV-03(재고 ≥ 0)·INV-01(잔액 ≥ 0)이 지켜지는지.
 * 현재 설계(3-6 동시성 제어 없음)에서는 lost update 로 실패할 수 있다.
 */
@SpringBootTest
class OrderConfirmConcurrencyTest {

    private static final int THREADS = 5;
    private static final String SUCCESS = "SUCCESS";

    @Autowired
    private OrderFacade orderFacade;
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
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("[OQ-03][INV-03] 재고 3개 상품을 5명이 동시에 1개씩 확정하면 3건만 성공하고 재고는 0.")
    @Test
    void concurrentConfirm_sameProduct_doesNotOversell() throws InterruptedException {
        ProductModel product = fixtures.product(brand.getId(), "상품", 1_000L, 3);
        List<Long> userIds = new ArrayList<>();
        List<Long> orderIds = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            UserModel user = fixtures.userWithBalance(10_000L);
            userIds.add(user.getId());
            orderIds.add(fixtures.draftOrder(user.getId(), product, 1).getId());
        }

        List<Object> results = runConcurrently(i -> orderFacade.confirmOrder(userIds.get(i), orderIds.get(i)));

        assertThat(results).filteredOn(SUCCESS::equals).hasSize(3);
        assertThat(results).filteredOn(r -> !SUCCESS.equals(r)).containsOnly(ErrorType.INSUFFICIENT_STOCK);
        assertThat(fixtures.reloadProduct(product.getId()).getStock()).isZero();
        assertThat(countConfirmed(orderIds)).isEqualTo(3);
    }

    @DisplayName("[OQ-03][INV-01] 잔액 3,000원 사용자가 1,000원 주문 5건을 동시에 확정하면 3건만 성공하고 잔액은 0.")
    @Test
    void concurrentConfirm_sameUser_doesNotOverdraw() throws InterruptedException {
        UserModel user = fixtures.userWithBalance(3_000L);
        List<Long> orderIds = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            // 재고 경합이 섞이지 않도록 주문마다 재고가 넉넉한 다른 상품을 쓴다.
            ProductModel product = fixtures.product(brand.getId(), "상품" + i, 1_000L, 10);
            orderIds.add(fixtures.draftOrder(user.getId(), product, 1).getId());
        }

        List<Object> results = runConcurrently(i -> orderFacade.confirmOrder(user.getId(), orderIds.get(i)));

        assertThat(results).filteredOn(SUCCESS::equals).hasSize(3);
        assertThat(results).filteredOn(r -> !SUCCESS.equals(r)).containsOnly(ErrorType.INSUFFICIENT_POINT);
        assertThat(fixtures.balanceOf(user.getId())).isZero();
        assertThat(countConfirmed(orderIds)).isEqualTo(3);
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

    private long countConfirmed(List<Long> orderIds) {
        return orderIds.stream()
            .map(fixtures::reloadOrder)
            .map(OrderModel::getStatus)
            .filter(OrderStatus.CONFIRMED::equals)
            .count();
    }

    @FunctionalInterface
    private interface IndexedTask {
        void run(int index);
    }
}
