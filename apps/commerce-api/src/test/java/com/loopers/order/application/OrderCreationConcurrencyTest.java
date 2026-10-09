package com.loopers.order.application;

import com.loopers.brand.domain.Brand;
import com.loopers.order.domain.Order;
import com.loopers.order.domain.OrderItem;
import com.loopers.order.domain.OrderStatus;
import com.loopers.order.infrastructure.OrderRepositoryAdapter;
import com.loopers.product.application.ProductUseCase;
import com.loopers.product.domain.Product;
import com.loopers.product.infrastructure.ProductRepositoryAdapter;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorCode;
import com.loopers.support.fixture.CommerceFixture;
import com.loopers.support.fixture.TestEntities;
import com.loopers.testcontainers.MySqlTestContainersConfig;
import com.loopers.user.domain.User;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;

@SpringBootTest
@Import(MySqlTestContainersConfig.class)
class OrderCreationConcurrencyTest {

    @Autowired private OrderUseCase orderUseCase;
    @Autowired private ProductUseCase productUseCase;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private DatabaseCleanUp databaseCleanUp;
    @MockitoSpyBean private OrderRepositoryAdapter orderRepository;
    @MockitoSpyBean private ProductRepositoryAdapter productRepository;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
        new CommerceFixture(entityManager, transactionManager).truncateRemainingTables();
    }

    @DisplayName("[R-ORDER-02, R-ORDER-04] 상품 확인부터 DRAFT 저장까지 수정·삭제를 기다리게 하고 생성 당시 이름·가격을 보존한다.")
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void holdsProductReadLockUntilDraftIsSaved(boolean delete) throws Exception {
        CommerceFixture fixture = new CommerceFixture(entityManager, transactionManager);
        Brand brand = fixture.brand("Nike");
        Product product = fixture.product(brand, "Air", 1_000L, 5);
        User buyer = fixture.userWithPoint(10_000L);
        CountDownLatch beforeSave = new CountDownLatch(1);
        CountDownLatch allowSave = new CountDownLatch(1);
        CountDownLatch mutationStarted = new CountDownLatch(1);
        doAnswer(invocation -> {
            beforeSave.countDown();
            await(allowSave);
            return invocation.callRealMethod();
        }).when(orderRepository).save(any(Order.class));
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<Order> creation = workers.submit(() -> orderUseCase.create(buyer.getId(),
                List.of(new OrderUseCase.ItemCommand(product.getId(), 2))));
            assertThat(beforeSave.await(10, TimeUnit.SECONDS)).isTrue();
            Future<?> mutation = workers.submit(() -> {
                mutationStarted.countDown();
                if (delete) {
                    productUseCase.delete(product.getId());
                } else {
                    productUseCase.update(product.getId(), "Air Max", 2_000L, brand.getId());
                }
            });
            assertThat(mutationStarted.await(10, TimeUnit.SECONDS)).isTrue();
            assertThrows(TimeoutException.class, () -> mutation.get(300, TimeUnit.MILLISECONDS));
            allowSave.countDown();
            Order order = creation.get(10, TimeUnit.SECONDS);
            mutation.get(10, TimeUnit.SECONDS);
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                Order saved = entityManager.find(Order.class, order.getId());
                Product changed = entityManager.find(Product.class, product.getId());
                assertThat(saved.getStatus()).isEqualTo(OrderStatus.DRAFT);
                assertThat(saved.getItems()).singleElement().satisfies(item -> {
                    assertThat(item.productName()).isEqualTo("Air");
                    assertThat(item.unitPrice()).isEqualTo(1_000L);
                    assertThat(item.quantity()).isEqualTo(2);
                });
                assertThat(saved.getTotalAmount()).isEqualTo(2_000L);
                assertThat(saved.getPaymentResult()).isNull();
                assertThat(changed.isDeleted()).isEqualTo(delete);
                if (!delete) {
                    assertThat(changed.getName()).isEqualTo("Air Max");
                    assertThat(changed.getPrice()).isEqualTo(2_000L);
                }
                assertThat(TestEntities.stockQuantity(entityManager, product.getId())).isEqualTo(5);
                assertThat(TestEntities.pointBalance(entityManager, buyer.getId())).isEqualTo(10_000L);
            });
        } finally {
            allowSave.countDown();
            workers.shutdownNow();
            assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @DisplayName("[R-ORDER-05] 상품 삭제가 먼저 잠금을 잡으면 주문 생성은 삭제 완료 후 거절하고 DRAFT를 남기지 않는다.")
    @Test
    void rejectsDraftAfterConcurrentProductDeletion() throws Exception {
        CommerceFixture fixture = new CommerceFixture(entityManager, transactionManager);
        Brand brand = fixture.brand("Nike");
        Product product = fixture.product(brand, "Air", 1_000L, 5);
        User buyer = fixture.userWithPoint(10_000L);
        CountDownLatch deleted = new CountDownLatch(1);
        CountDownLatch allowCommit = new CountDownLatch(1);
        CountDownLatch creationStarted = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<?> deletion = workers.submit(() ->
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    productUseCase.delete(product.getId());
                    entityManager.flush();
                    deleted.countDown();
                    await(allowCommit);
                }));
            assertThat(deleted.await(10, TimeUnit.SECONDS)).isTrue();
            Future<ErrorCode> creation = workers.submit(() -> {
                creationStarted.countDown();
                try {
                    orderUseCase.create(buyer.getId(),
                        List.of(new OrderUseCase.ItemCommand(product.getId(), 1)));
                    return null;
                } catch (CoreException exception) {
                    return exception.getErrorCode();
                }
            });
            assertThat(creationStarted.await(10, TimeUnit.SECONDS)).isTrue();
            assertThrows(TimeoutException.class, () -> creation.get(300, TimeUnit.MILLISECONDS));
            allowCommit.countDown();
            deletion.get(10, TimeUnit.SECONDS);
            assertThat(creation.get(10, TimeUnit.SECONDS)).isEqualTo(ErrorCode.PRODUCT_NOT_FOUND);
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                assertThat(entityManager.createQuery("select o from Order o", Order.class).getResultList())
                    .isEmpty();
                assertThat(TestEntities.stockQuantity(entityManager, product.getId())).isEqualTo(5);
                assertThat(TestEntities.pointBalance(entityManager, buyer.getId())).isEqualTo(10_000L);
            });
        } finally {
            allowCommit.countDown();
            workers.shutdownNow();
            assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @DisplayName("[R-ORDER-02, R-ORDER-15] 상품은 ID 순서로 한 번씩 잠그고 품목의 요청 순서와 중복 수량 합산은 유지한다.")
    @Test
    void locksDistinctProductsInIdOrderAndPreservesItemContract() {
        CommerceFixture fixture = new CommerceFixture(entityManager, transactionManager);
        Brand brand = fixture.brand("Nike");
        Product first = fixture.product(brand, "Air", 1_000L, 5);
        Product second = fixture.product(brand, "Dunk", 2_000L, 5);
        User buyer = fixture.userWithPoint(10_000L);
        Order order = orderUseCase.create(buyer.getId(), List.of(
            new OrderUseCase.ItemCommand(second.getId(), 1),
            new OrderUseCase.ItemCommand(first.getId(), 1),
            new OrderUseCase.ItemCommand(second.getId(), 2)
        ));

        InOrder lockOrder = inOrder(productRepository);
        lockOrder.verify(productRepository).findForOrder(first.getId());
        lockOrder.verify(productRepository).findForOrder(second.getId());
        lockOrder.verifyNoMoreInteractions();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            List<OrderItem> items = entityManager.find(Order.class, order.getId()).getItems();
            assertThat(items).extracting(OrderItem::productId)
                .containsExactly(second.getId(), first.getId());
            assertThat(items).extracting(OrderItem::quantity).containsExactly(3, 1);
        });
    }

    @DisplayName("[R-ORDER-04] 같은 상품의 공유락을 두 주문 생성이 함께 획득하고 재고·잔액을 차감하지 않는다.")
    @Test
    void allowsConcurrentDraftCreationForSameProduct() throws Exception {
        CommerceFixture fixture = new CommerceFixture(entityManager, transactionManager);
        Brand brand = fixture.brand("Nike");
        Product product = fixture.product(brand, "Air", 1_000L, 5);
        User buyer = fixture.userWithPoint(10_000L);
        CountDownLatch bothBeforeSave = new CountDownLatch(2);
        CountDownLatch allowSave = new CountDownLatch(1);
        doAnswer(invocation -> {
            bothBeforeSave.countDown();
            await(allowSave);
            return invocation.callRealMethod();
        }).when(orderRepository).save(any(Order.class));
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<Order> first = workers.submit(() -> orderUseCase.create(buyer.getId(),
                List.of(new OrderUseCase.ItemCommand(product.getId(), 1))));
            Future<Order> second = workers.submit(() -> orderUseCase.create(buyer.getId(),
                List.of(new OrderUseCase.ItemCommand(product.getId(), 1))));
            assertThat(bothBeforeSave.await(10, TimeUnit.SECONDS)).isTrue();
            allowSave.countDown();
            Order firstOrder = first.get(10, TimeUnit.SECONDS);
            Order secondOrder = second.get(10, TimeUnit.SECONDS);
            assertThat(firstOrder.getId()).isNotEqualTo(secondOrder.getId());
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                assertThat(entityManager.find(Order.class, firstOrder.getId()).getStatus()).isEqualTo(OrderStatus.DRAFT);
                assertThat(entityManager.find(Order.class, secondOrder.getId()).getStatus()).isEqualTo(OrderStatus.DRAFT);
                assertThat(TestEntities.stockQuantity(entityManager, product.getId())).isEqualTo(5);
                assertThat(TestEntities.pointBalance(entityManager, buyer.getId())).isEqualTo(10_000L);
            });
        } finally {
            allowSave.countDown();
            workers.shutdownNow();
            assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out waiting to save draft or commit deletion");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for transaction", exception);
        }
    }
}
