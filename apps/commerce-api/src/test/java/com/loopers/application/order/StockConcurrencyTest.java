package com.loopers.application.order;

import com.loopers.application.user.UserRegistrationService;
import com.loopers.application.point.PointFacade;
import com.loopers.application.product.ProductFacade;
import com.loopers.application.brand.BrandFacade;
import com.loopers.domain.order.OrderModel;
import com.loopers.infrastructure.order.OrderRepositoryImpl;
import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.product.ProductModel;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.order.OrderJpaRepository;
import com.loopers.infrastructure.point.PointBalanceJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.infrastructure.user.UserJpaRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

@SpringBootTest
class StockConcurrencyTest {
    @Autowired private UserRegistrationService registration;
    @Autowired private OrderFacade orders;
    @Autowired private ProductFacade productFacade;
    @Autowired private BrandFacade brandFacade;
    @MockitoSpyBean private OrderRepositoryImpl orderPersistence;
    @Autowired private PointFacade points;
    @Autowired private UserJpaRepository users;
    @Autowired private BrandJpaRepository brands;
    @Autowired private ProductJpaRepository products;
    @Autowired private OrderJpaRepository orderRepository;
    @Autowired private PointBalanceJpaRepository balances;
    @Autowired private EntityManager entityManager;
    @Autowired private TransactionTemplate transaction;
    @Autowired private DatabaseCleanUp cleanUp;

    @AfterEach
    void clean() {
        reset(orderPersistence);
        cleanUp.truncateAllTables();
    }

    @Test
    void demonstratesLostUpdateInUnlockedConstantWriteControl() throws Exception {
        long productId = product(5).getId();
        CountDownLatch bothRead = new CountDownLatch(2);
        Callable<Integer> unsafeDecrement = () -> transaction.execute(status -> {
            int before = ((Number) entityManager.createNativeQuery(
                "select quantity from product where id = :id")
                .setParameter("id", productId).getSingleResult()).intValue();
            assertThat(before).isEqualTo(5);
            bothRead.countDown();
            await(bothRead); // 대조군에만 둔다. 실제 서비스의 읽기 뒤에는 장벽을 두지 않는다.
            entityManager.createNativeQuery("update product set quantity = :quantity where id = :id")
                .setParameter("quantity", before - 1).setParameter("id", productId).executeUpdate();
            return 1;
        });

        List<Integer> successes = simultaneously(List.of(unsafeDecrement, unsafeDecrement));

        int remaining = products.findById(productId).orElseThrow().getStockQuantity();
        assertThat(successes).containsExactly(1, 1);
        assertThat(remaining).isEqualTo(4);
        assertThat(successes.size() + remaining).isNotEqualTo(5);
    }

    @Test
    void confirmsFiveOfEightOrdersAndPreservesRejectedOrdersAndBalances() throws Exception {
        long productId = product(5).getId();
        List<Purchase> purchases = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            purchases.add(purchase(List.of(new OrderFacade.OrderLine(productId, 1))));
        }

        List<Result> results = simultaneously(purchases.stream()
            .<Callable<Result>>map(purchase -> () -> confirm(purchase)).toList());

        assertThat(results.stream().filter(result -> result.failure() != null).toList()).isEmpty();
        assertThat(results.stream().filter(Result::confirmed).count()).isEqualTo(5);
        assertThat(results.stream().filter(result -> !result.confirmed()).count()).isEqualTo(3);
        transaction.executeWithoutResult(status -> {
            int sold = 0;
            long paid = 0;
            for (Result result : results) {
                var order = orderRepository.findById(result.purchase().orderId()).orElseThrow();
                long balance = balances.findByUserId(result.purchase().userId()).orElseThrow().getBalance();
                assertThat(order.getStatus()).isEqualTo(result.confirmed() ? OrderStatus.CONFIRMED : OrderStatus.DRAFT);
                assertThat(order.getPaidAmount()).isEqualTo(result.confirmed() ? 1_000L : 0L);
                assertThat(balance).isEqualTo(result.confirmed() ? 9_020L : 10_000L);
                if (result.confirmed()) {
                    sold += order.getItems().stream().mapToInt(item -> item.getQuantity()).sum();
                    paid += order.getPaidAmount();
                }
            }
            int remaining = products.findById(productId).orElseThrow().getStockQuantity();
            assertThat(remaining).isZero();
            assertThat(sold + remaining).isEqualTo(5);
            assertThat(paid).isEqualTo(5_000L);
        });
    }

    @Test
    void confirmsOrdersWithOppositeItemOrderWithoutMixingQuantities() throws Exception {
        long firstId = product(5).getId();
        long secondId = product(5).getId();
        Purchase first = purchase(List.of(
            new OrderFacade.OrderLine(firstId, 1), new OrderFacade.OrderLine(secondId, 2)));
        Purchase second = purchase(List.of(
            new OrderFacade.OrderLine(secondId, 1), new OrderFacade.OrderLine(firstId, 2)));

        List<Result> results = simultaneously(List.of(() -> confirm(first), () -> confirm(second)));

        assertThat(results).allSatisfy(result -> {
            assertThat(result.failure()).isNull();
            assertThat(result.confirmed()).isTrue();
        });
        transaction.executeWithoutResult(status -> {
            assertThat(products.findById(firstId).orElseThrow().getStockQuantity()).isEqualTo(2);
            assertThat(products.findById(secondId).orElseThrow().getStockQuantity()).isEqualTo(2);
            for (Purchase purchase : List.of(first, second)) {
                assertThat(orderRepository.findById(purchase.orderId()).orElseThrow().getPaidAmount()).isEqualTo(3_000L);
                assertThat(balances.findByUserId(purchase.userId()).orElseThrow().getBalance()).isEqualTo(7_060L);
            }
        });
    }

    @Test
    void stockSettingAndOrderConfirmationHaveAValidSerialResult() throws Exception {
        long productId = product(5).getId();
        Purchase purchase = purchase(List.of(new OrderFacade.OrderLine(productId, 1)));

        List<Result> results = simultaneously(List.of(
            () -> confirm(purchase),
            () -> {
                productFacade.changeStock(productId, 0);
                return new Result(purchase, false, null);
            }));

        Result confirmation = results.getFirst();
        assertThat(confirmation.failure()).isNull();
        transaction.executeWithoutResult(status -> {
            assertThat(products.findById(productId).orElseThrow().getStockQuantity()).isZero();
            assertThat(orderRepository.findById(purchase.orderId()).orElseThrow().getStatus())
                .isEqualTo(confirmation.confirmed() ? OrderStatus.CONFIRMED : OrderStatus.DRAFT);
            assertThat(balances.findByUserId(purchase.userId()).orElseThrow().getBalance())
                .isEqualTo(confirmation.confirmed() ? 9_020L : 10_000L);
        });
    }

    @Test
    void productEditDoesNotOverwriteSoldStockOrOrderSnapshot() throws Exception {
        long productId = product(5).getId();
        Purchase purchase = purchase(List.of(new OrderFacade.OrderLine(productId, 1)));

        List<Result> results = simultaneously(List.of(
            () -> confirm(purchase),
            () -> {
                productFacade.update(productId, "updated", 2_000L);
                return new Result(purchase, false, null);
            }));

        assertThat(results.getFirst().failure()).isNull();
        assertThat(results.getFirst().confirmed()).isTrue();
        transaction.executeWithoutResult(status -> {
            ProductModel product = products.findById(productId).orElseThrow();
            assertThat(product.getStockQuantity()).isEqualTo(4);
            assertThat(product.getName()).isEqualTo("updated");
            assertThat(product.getPrice()).isEqualTo(2_000L);
            assertThat(orderRepository.findById(purchase.orderId()).orElseThrow().getPaidAmount()).isEqualTo(1_000L);
        });
    }

    @Test
    void brandDeletionDoesNotOverwriteSoldStock() throws Exception {
        ProductModel product = product(5);
        Purchase purchase = purchase(List.of(new OrderFacade.OrderLine(product.getId(), 1)));

        List<Boolean> results = simultaneously(List.of(
            () -> {
                try {
                    orders.confirm(purchase.userId(), purchase.orderId());
                    return true;
                } catch (CoreException exception) {
                    assertThat(exception.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
                    return false;
                }
            },
            () -> {
                brandFacade.delete(product.getBrandId());
                return false;
            }));

        boolean confirmed = results.getFirst();
        transaction.executeWithoutResult(status -> {
            ProductModel stored = products.findById(product.getId()).orElseThrow();
            assertThat(stored.getDeletedAt()).isNotNull();
            assertThat(stored.getStockQuantity()).isEqualTo(confirmed ? 4 : 5);
            assertThat(orderRepository.findById(purchase.orderId()).orElseThrow().getStatus())
                .isEqualTo(confirmed ? OrderStatus.CONFIRMED : OrderStatus.DRAFT);
            assertThat(balances.findByUserId(purchase.userId()).orElseThrow().getBalance())
                .isEqualTo(confirmed ? 9_020L : 10_000L);
        });
    }

    @Test
    void lockOnOneProductDoesNotBlockPurchaseOfAnotherProduct() throws Exception {
        ProductModel locked = product(5);
        ProductModel other = products.save(new ProductModel(locked.getBrandId(), "other", 1_000L, 5));
        Purchase purchase = purchase(List.of(new OrderFacade.OrderLine(other.getId(), 1)));
        var executor = Executors.newSingleThreadExecutor();
        try {
            transaction.executeWithoutResult(status -> {
                entityManager.createNativeQuery("select id from product where id = :id for update")
                    .setParameter("id", locked.getId()).getSingleResult();
                // J의 잠금을 유지한 채 다른 연결에서 K의 실제 주문 확정이 끝나는지 확인한다.
                Future<Result> future = executor.submit(() -> confirm(purchase));
                try {
                    Result result = future.get(10, TimeUnit.SECONDS);
                    assertThat(result.failure()).isNull();
                    assertThat(result.confirmed()).isTrue();
                } catch (Exception exception) {
                    throw new AssertionError("다른 상품의 주문이 완료되어야 한다", exception);
                } finally {
                    future.cancel(true);
                }
            });
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(products.findById(locked.getId()).orElseThrow().getStockQuantity()).isEqualTo(5);
        assertThat(products.findById(other.getId()).orElseThrow().getStockQuantity()).isEqualTo(4);
    }

    @Test
    void rollsBackFlushedStockPaymentAndRewardWhenOrderSaveFails() {
        long productId = product(5).getId();
        Purchase purchase = purchase(List.of(new OrderFacade.OrderLine(productId, 1)));
        doAnswer(invocation -> {
            invocation.callRealMethod();
            entityManager.flush();
            assertThat(((Number) entityManager.createNativeQuery("select quantity from product where id = :id")
                .setParameter("id", productId).getSingleResult()).intValue()).isEqualTo(4);
            assertThat(((Number) entityManager.createNativeQuery("select balance from point_balance where user_id = :id")
                .setParameter("id", purchase.userId()).getSingleResult()).longValue()).isEqualTo(9_020L);
            throw new IllegalStateException("test: failure after actual updates");
        }).when(orderPersistence).save(any(OrderModel.class));

        assertThatThrownBy(() -> orders.confirm(purchase.userId(), purchase.orderId()))
            .isInstanceOf(IllegalStateException.class).hasMessage("test: failure after actual updates");

        transaction.executeWithoutResult(status -> {
            assertThat(products.findById(productId).orElseThrow().getStockQuantity()).isEqualTo(5);
            var order = orderRepository.findById(purchase.orderId()).orElseThrow();
            assertThat(order.getStatus()).isEqualTo(OrderStatus.DRAFT);
            assertThat(order.getPaidAmount()).isZero();
            assertThat(balances.findByUserId(purchase.userId()).orElseThrow().getBalance()).isEqualTo(10_000L);
            assertThat(((Number) entityManager.createNativeQuery("select count(*) from point_usage")
                .getSingleResult()).longValue()).isZero();
            assertThat(((Number) entityManager.createNativeQuery("select count(*) from point_grant")
                .getSingleResult()).longValue()).isEqualTo(1L);
        });
        reset(orderPersistence);
        assertThat(orders.confirm(purchase.userId(), purchase.orderId()).status()).isEqualTo(OrderStatus.CONFIRMED.name());
    }

    private ProductModel product(int stock) {
        BrandModel brand = brands.save(new BrandModel("brand", null));
        return products.save(new ProductModel(brand.getId(), "product", 1_000L, stock));
    }

    private Purchase purchase(List<OrderFacade.OrderLine> items) {
        long userId = registration.register().getId();
        points.charge(userId, 10_000L);
        return new Purchase(userId, orders.create(userId, items).id());
    }

    private Result confirm(Purchase purchase) {
        try {
            orders.confirm(purchase.userId(), purchase.orderId());
            return new Result(purchase, true, null);
        } catch (CoreException exception) {
            if (exception.getErrorType() == ErrorType.CONFLICT && "재고가 부족합니다.".equals(exception.getMessage())) {
                return new Result(purchase, false, null);
            }
            return new Result(purchase, false, exception);
        } catch (Exception exception) {
            return new Result(purchase, false, exception);
        }
    }

    private static <T> List<T> simultaneously(List<Callable<T>> tasks) throws Exception {
        var executor = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        try {
            for (Callable<T> task : tasks) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    await(start);
                    return task.call();
                }));
            }
            await(ready);
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            start.countDown();
            futures.forEach(future -> future.cancel(true));
            executor.shutdownNow();
            assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test coordination timed out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("test interrupted", exception);
        }
    }

    private record Purchase(long userId, long orderId) {}
    private record Result(Purchase purchase, boolean confirmed, Exception failure) {}
}
