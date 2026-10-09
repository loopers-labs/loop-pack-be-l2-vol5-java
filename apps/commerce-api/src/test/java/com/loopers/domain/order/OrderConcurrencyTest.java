package com.loopers.domain.order;

import com.loopers.application.order.OrderFacade;
import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.point.PointModel;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.user.UserModel;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.order.OrderJpaRepository;
import com.loopers.infrastructure.point.PointJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.infrastructure.user.UserJpaRepository;
import com.loopers.support.error.CoreException;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class OrderConcurrencyTest {

    @Autowired UserJpaRepository userJpaRepository;
    @Autowired BrandJpaRepository brandJpaRepository;
    @Autowired ProductJpaRepository productJpaRepository;
    @Autowired PointJpaRepository pointJpaRepository;
    @Autowired OrderJpaRepository orderJpaRepository;

    @Autowired OrderFacade orderFacade;
    @Autowired PlatformTransactionManager transactionManager;

    @Autowired DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    // ────────────────────────────────────────────────────────────────
    // 대조군: 잠금 없이 읽으면 갱신이 유실됨을 보여주는 음성 예제
    // 이 테스트는 의도적으로 '잘못된 결과'를 assert 합니다.
    // ────────────────────────────────────────────────────────────────
    @DisplayName("[대조군] 잠금 없이 동시에 읽고 쓰면 갱신이 유실되어 재고가 4가 된다.")
    @Test
    void lostUpdate_withoutLock() throws InterruptedException {
        // 준비: 재고 5짜리 상품
        BrandModel brand = brandJpaRepository.save(new BrandModel("브랜드"));
        ProductModel product = productJpaRepository.save(
            new ProductModel(brand.getId(), "상품", 10_000L, 5)
        );
        Long productId = product.getId();

        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);

        // 두 스레드가 "읽기 완료" 신호를 보내고 동시에 쓰기 시작하도록 하는 barrier
        CountDownLatch bothRead = new CountDownLatch(2);   // 둘 다 읽으면 열림
        CountDownLatch startWrite = new CountDownLatch(1); // 열리면 동시에 쓰기 시작

        AtomicInteger successCount = getAtomicInteger();

        Runnable task = () -> txTemplate.execute(status -> {
            // 1. 잠금 없이 읽기
            ProductModel p = productJpaRepository.findByIdAndDeletedAtIsNull(productId).get();
            int readStock = p.getStock(); // 두 스레드 모두 5를 읽음

            bothRead.countDown();          // "나는 읽었다" 신호
            try {
                startWrite.await(5, TimeUnit.SECONDS); // 둘 다 읽을 때까지 대기
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            // 2. 읽어둔 값에서 1 뺀 상수 4를 저장 (잠금·version 없이)
            p.changeStock(readStock - 1); // 5 - 1 = 4
            productJpaRepository.save(p);
            successCount.incrementAndGet();
            return null;
        });

        ExecutorService executor = Executors.newFixedThreadPool(2);
        executor.submit(task);
        executor.submit(task);

        bothRead.await(5, TimeUnit.SECONDS); // 둘 다 읽을 때까지 대기
        startWrite.countDown();              // 동시에 쓰기 시작

        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        // 두 스레드 모두 성공했지만 최종 재고는 4 (3이 아님) — 갱신 유실
        ProductModel result = productJpaRepository.findById(productId).get();
        assertThat(successCount.get()).isEqualTo(2);
        assertThat(result.getStock()).isEqualTo(4); // 5 - 1 - 1 = 3 이어야 하지만 4
    }

    private static AtomicInteger getAtomicInteger() {
        AtomicInteger successCount = new AtomicInteger(0);
        return successCount;
    }

    // ────────────────────────────────────────────────────────────────
    // 실서비스: 재고 5에 8명이 동시에 1개씩 주문 확정
    // 기대: 성공 5 / 재고 부족 3 / 기술 오류 0 / 최종 재고 0
    // ────────────────────────────────────────────────────────────────
    @DisplayName("재고 5에 8개 주문이 동시에 확정되면 5개만 성공하고 최종 재고는 0이다.")
    @Test
    void stockCompetition_onlyFiveSucceed() throws InterruptedException {
        int stockCount = 5;
        int orderCount = 8;

        // 준비: 상품 1개 (재고 5)
        BrandModel brand = brandJpaRepository.save(new BrandModel("브랜드"));
        ProductModel product = productJpaRepository.save(
            new ProductModel(brand.getId(), "상품", 10_000L, stockCount)
        );

        // 구매자 8명, 각자 충분한 포인트와 DRAFT 주문 1개
        List<Long> userIds = new ArrayList<>();
        List<Long> orderIds = new ArrayList<>();
        for (int i = 0; i < orderCount; i++) {
            UserModel user = userJpaRepository.save(new UserModel("buyer" + i));
            PointModel point = new PointModel(user.getId());
            point.charge(100_000L);
            pointJpaRepository.save(point);

            OrderModel order = orderJpaRepository.save(
                new OrderModel(user.getId(), List.of(
                    new OrderItemModel(product.getId(), 1, product.getPrice())
                ))
            );
            userIds.add(user.getId());
            orderIds.add(order.getId());
        }

        // 동시 실행
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(orderCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger businessRejected = new AtomicInteger(0);
        AtomicInteger technicalErrors = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(orderCount);
        for (int i = 0; i < orderCount; i++) {
            final Long userId = userIds.get(i);
            final Long orderId = orderIds.get(i);
            executor.submit(() -> {
                try {
                    startLatch.await(5, TimeUnit.SECONDS);
                    orderFacade.confirmOrder(userId, orderId);
                    successCount.incrementAndGet();
                } catch (CoreException e) {
                    businessRejected.incrementAndGet(); // 재고 부족 등 업무 거절
                } catch (Exception e) {
                    technicalErrors.incrementAndGet(); // 예상치 못한 기술 오류
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown(); // 동시 시작
        doneLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        // 결과 검증
        ProductModel finalProduct = productJpaRepository.findById(product.getId()).get();
        long confirmedQuantitySum = orderJpaRepository.findAll().stream()
            .filter(o -> o.getStatus() == OrderStatus.CONFIRMED)
            .flatMap(o -> o.getItems().stream())
            .mapToLong(OrderItemModel::getQuantity)
            .sum();

        assertThat(technicalErrors.get()).isEqualTo(0);
        assertThat(successCount.get()).isEqualTo(stockCount);
        assertThat(businessRejected.get()).isEqualTo(orderCount - stockCount);
        assertThat(finalProduct.getStock()).isEqualTo(0);
        // 수량 불변식: 초기 재고 - 성공 주문 수량 합 = 최종 재고
        assertThat(stockCount - confirmedQuantitySum).isEqualTo(finalProduct.getStock());
    }

    // ────────────────────────────────────────────────────────────────
    // 실서비스: 잔액 10,000원에 4,000원짜리 주문 3개 동시 확정
    // 기대: 성공 2 / 잔액 부족 1 / 기술 오류 0 / 최종 잔액 2,000
    // ────────────────────────────────────────────────────────────────
    @DisplayName("잔액 10,000원에 4,000원 주문 3개가 동시에 확정되면 2개만 성공하고 최종 잔액은 2,000원이다.")
    @Test
    void pointCompetition_onlyTwoSucceed() throws InterruptedException {
        int orderCount = 3;
        long initialBalance = 10_000L;
        long orderAmount = 4_000L;

        // 준비: 사용자 1명, 잔액 10,000원
        UserModel user = userJpaRepository.save(new UserModel("구매자"));
        PointModel point = new PointModel(user.getId());
        point.charge(initialBalance);
        pointJpaRepository.save(point);

        // 재고는 넉넉하게 (포인트 경쟁만 확인)
        BrandModel brand = brandJpaRepository.save(new BrandModel("브랜드"));
        ProductModel product = productJpaRepository.save(
            new ProductModel(brand.getId(), "상품", orderAmount, 100)
        );

        // 4,000원짜리 DRAFT 주문 3개
        List<Long> orderIds = new ArrayList<>();
        for (int i = 0; i < orderCount; i++) {
            OrderModel order = orderJpaRepository.save(
                new OrderModel(user.getId(), List.of(
                    new OrderItemModel(product.getId(), 1, orderAmount)
                ))
            );
            orderIds.add(order.getId());
        }

        // 동시 실행
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(orderCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger businessRejected = new AtomicInteger(0);
        AtomicInteger technicalErrors = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(orderCount);
        for (int i = 0; i < orderCount; i++) {
            final Long orderId = orderIds.get(i);
            executor.submit(() -> {
                try {
                    startLatch.await(5, TimeUnit.SECONDS);
                    orderFacade.confirmOrder(user.getId(), orderId);
                    successCount.incrementAndGet();
                } catch (CoreException e) {
                    businessRejected.incrementAndGet();
                } catch (Exception e) {
                    technicalErrors.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        // 결과 검증
        PointModel finalPoint = pointJpaRepository.findByUserId(user.getId()).get();
        long paidSum = orderJpaRepository.findAllByUserId(user.getId()).stream()
            .filter(o -> o.getStatus() == OrderStatus.CONFIRMED)
            .mapToLong(OrderModel::getTotalAmount)
            .sum();

        assertThat(technicalErrors.get()).isEqualTo(0);
        assertThat(successCount.get()).isEqualTo(2);
        assertThat(businessRejected.get()).isEqualTo(1);
        assertThat(finalPoint.getBalance()).isEqualTo(2_000L);
        // 잔액 불변식: 초기 잔액 - 성공 결제액 합 = 최종 잔액
        assertThat(initialBalance - paidSum).isEqualTo(finalPoint.getBalance());
    }
}
