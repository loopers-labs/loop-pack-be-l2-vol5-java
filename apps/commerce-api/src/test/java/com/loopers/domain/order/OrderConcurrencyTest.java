package com.loopers.domain.order;

import com.loopers.application.order.OrderEventPublisher;
import com.loopers.application.order.OrderFacade;
import com.loopers.application.payment.PgPaymentPort;
import com.loopers.application.payment.PgPaymentResult;
import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.user.UserModel;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.order.OrderJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.infrastructure.user.UserJpaRepository;
import com.loopers.support.error.CoreException;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest
class OrderConcurrencyTest {

    @Autowired UserJpaRepository userJpaRepository;
    @Autowired BrandJpaRepository brandJpaRepository;
    @Autowired ProductJpaRepository productJpaRepository;
    @Autowired OrderJpaRepository orderJpaRepository;

    @Autowired OrderFacade orderFacade;
    @Autowired PlatformTransactionManager transactionManager;

    @MockBean PgPaymentPort pgPaymentPort;
    @MockBean OrderEventPublisher orderEventPublisher;

    @Autowired DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("[대조군] 잠금 없이 동시에 읽고 쓰면 갱신이 유실되어 재고가 4가 된다.")
    @Test
    void lostUpdate_withoutLock() throws InterruptedException {
        BrandModel brand = brandJpaRepository.save(new BrandModel("브랜드"));
        ProductModel product = productJpaRepository.save(
            new ProductModel(brand.getId(), "상품", 10_000L, 5)
        );
        Long productId = product.getId();

        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);

        CountDownLatch bothRead = new CountDownLatch(2);
        CountDownLatch startWrite = new CountDownLatch(1);

        AtomicInteger successCount = new AtomicInteger(0);

        Runnable task = () -> txTemplate.execute(status -> {
            ProductModel p = productJpaRepository.findByIdAndDeletedAtIsNull(productId).get();
            int readStock = p.getStock();

            bothRead.countDown();
            try {
                startWrite.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            p.changeStock(readStock - 1);
            productJpaRepository.save(p);
            successCount.incrementAndGet();
            return null;
        });

        ExecutorService executor = Executors.newFixedThreadPool(2);
        executor.submit(task);
        executor.submit(task);

        bothRead.await(5, TimeUnit.SECONDS);
        startWrite.countDown();

        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        ProductModel result = productJpaRepository.findById(productId).get();
        assertThat(successCount.get()).isEqualTo(2);
        assertThat(result.getStock()).isEqualTo(4);
    }

    @DisplayName("재고 5에 8개 주문이 동시에 결제를 시작하면 5개만 성공하고 최종 재고는 0이다.")
    @Test
    void stockCompetition_onlyFiveSucceed() throws InterruptedException {
        int stockCount = 5;
        int orderCount = 8;

        when(pgPaymentPort.pay(any())).thenReturn(
            new PgPaymentResult("TX-SUCCESS", "LP-ORD-0000000001", 10_000L, PgPaymentResult.PgStatus.SUCCESS)
        );

        BrandModel brand = brandJpaRepository.save(new BrandModel("브랜드"));
        ProductModel product = productJpaRepository.save(
            new ProductModel(brand.getId(), "상품", 10_000L, stockCount)
        );

        List<Long> userIds = new ArrayList<>();
        List<Long> orderIds = new ArrayList<>();
        for (int i = 0; i < orderCount; i++) {
            UserModel user = userJpaRepository.save(new UserModel("buyer" + i));

            OrderModel order = orderJpaRepository.save(
                new OrderModel(user.getId(), List.of(
                    new OrderItemModel(product.getId(), 1, product.getPrice())
                ))
            );
            userIds.add(user.getId());
            orderIds.add(order.getId());
        }

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(orderCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger businessRejected = new AtomicInteger(0);
        AtomicInteger technicalErrors = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(orderCount);
        for (int i = 0; i < orderCount; i++) {
            final Long userId = userIds.get(i);
            final Long orderId = orderIds.get(i);
            final String attemptKey = UUID.randomUUID().toString();
            executor.submit(() -> {
                try {
                    startLatch.await(5, TimeUnit.SECONDS);
                    orderFacade.confirmOrder(userId, orderId, attemptKey, "http://localhost/callback");
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
        assertThat(stockCount - confirmedQuantitySum).isEqualTo(finalProduct.getStock());
    }
}
