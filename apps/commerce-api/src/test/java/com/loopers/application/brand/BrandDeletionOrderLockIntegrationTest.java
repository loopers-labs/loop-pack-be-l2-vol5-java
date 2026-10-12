package com.loopers.application.brand;

import com.loopers.application.order.OrderFacade;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderRepository;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.point.Point;
import com.loopers.domain.point.PointBalance;
import com.loopers.domain.point.PointRepository;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.user.User;
import com.loopers.domain.user.UserRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static com.loopers.application.brand.BrandLockTestSupport.TIMEOUT_SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@SpringBootTest
class BrandDeletionOrderLockIntegrationTest {

    @Autowired private BrandFacade brandFacade;
    @Autowired private OrderFacade orderFacade;
    @MockitoSpyBean private BrandRepository brandRepository;
    @MockitoSpyBean private ProductRepository productRepository;
    @MockitoSpyBean private OrderRepository orderRepository;
    @Autowired private PointRepository pointRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private DatabaseCleanUp databaseCleanUp;

    private BrandLockTestSupport locks;

    @BeforeEach
    void setUp() {
        locks = new BrandLockTestSupport(dataSource, jdbcTemplate, transactionManager);
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @Test
    @DisplayName("주문 상태를 갱신하기 전에 중복 없이 Brand ID 순서로 공유 잠금을 얻는다")
    void confirmationLocksDistinctBrandsInIdOrderBeforeUpdatingOrder() {
        Fixture fixture = createFixture();
        Brand secondBrand = brandRepository.save(Brand.create("Adidas"));
        Product secondProduct = saveProduct(secondBrand.getId(), "Second");
        Product thirdProduct = saveProduct(secondBrand.getId(), "Third");
        Order order = orderRepository.save(Order.create(fixture.userId(), List.of(
            OrderItem.create(thirdProduct.getId(), "Third", 100L, 1),
            OrderItem.create(fixture.productId(), "First", 100L, 1),
            OrderItem.create(secondProduct.getId(), "Second", 100L, 1)
        )));

        doAnswer(invocation -> {
            locks.assertSharedLock(fixture.brandId());
            locks.assertSharedLock(secondBrand.getId());
            InOrder calls = inOrder(brandRepository);
            calls.verify(brandRepository).findActiveByIdWithSharedLock(fixture.brandId());
            calls.verify(brandRepository).findActiveByIdWithSharedLock(secondBrand.getId());
            return invocation.callRealMethod();
        }).when(orderRepository).confirmIfDraft(eq(order.getId()), eq(fixture.userId()), eq(300L), any());

        orderFacade.confirm(fixture.userId(), order.getId());

        verify(brandRepository, times(1)).findActiveByIdWithSharedLock(fixture.brandId());
        verify(brandRepository, times(1)).findActiveByIdWithSharedLock(secondBrand.getId());
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            assertThat(orderRepository.findById(order.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.CONFIRMED);
            assertThat(pointRepository.findByUserId(fixture.userId()).orElseThrow().getBalance().amount())
                .isEqualTo(700L);
            for (Long productId : List.of(fixture.productId(), secondProduct.getId(), thirdProduct.getId())) {
                assertThat(productRepository.findById(productId).orElseThrow().getStock().amount())
                    .isEqualTo(4L);
            }
        });
    }

    @Test
    @DisplayName("삭제가 Brand 잠금을 먼저 얻으면 확정은 기다린 뒤 거절되고 차감하지 않는다")
    void confirmationWaitsForDeletionBeforeAnyProductIsDeleted() throws Exception {
        Fixture fixture = createFixture();
        CountDownLatch deletionLocked = new CountDownLatch(1);
        CountDownLatch allowDeletion = new CountDownLatch(1);
        CountDownLatch confirmationStarted = new CountDownLatch(1);
        AtomicLong confirmationConnection = new AtomicLong();
        ExecutorService workers = Executors.newFixedThreadPool(2);

        // Brand 잠금 직후, Product UPDATE 전에 삭제를 잠시 멈춰 시작 순서의 계약을 확인한다.
        doAnswer(invocation -> {
            deletionLocked.countDown();
            locks.awaitLatch(allowDeletion);
            return invocation.callRealMethod();
        }).when(productRepository).softDeleteActiveByBrandId(eq(fixture.brandId()), any());

        try {
            Future<?> deletion = locks.submit(workers, () -> brandFacade.delete(fixture.brandId()));
            assertThat(deletionLocked.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            Future<?> confirmation = locks.submit(workers, confirmationConnection, confirmationStarted,
                () -> orderFacade.confirm(fixture.userId(), fixture.orderId()));
            assertThat(confirmationStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            locks.awaitLockWait(confirmationConnection.get(), "brands");

            allowDeletion.countDown();
            deletion.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertThatThrownBy(() -> confirmation.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class).cause()
                .isInstanceOfSatisfying(CoreException.class, exception ->
                    assertThat(exception.getErrorType()).isEqualTo(ErrorType.CONFLICT));
            assertFinalState(fixture, true, OrderStatus.DRAFT, 5L, 1_000L);
        } finally {
            allowDeletion.countDown();
            locks.shutDown(workers);
        }
    }

    @Test
    @DisplayName("확정이 먼저 공유 잠금을 얻으면 삭제는 확정 종료까지 기다리고 결제 정보를 보존한다")
    void deletionWaitsForConfirmationAndPreservesPayment() throws Exception {
        Fixture fixture = createFixture();
        CountDownLatch confirmationLocked = new CountDownLatch(1);
        CountDownLatch allowConfirmation = new CountDownLatch(1);
        CountDownLatch deletionStarted = new CountDownLatch(1);
        AtomicLong deletionConnection = new AtomicLong();
        ExecutorService workers = Executors.newFixedThreadPool(2);

        doAnswer(invocation -> {
            confirmationLocked.countDown();
            locks.awaitLatch(allowConfirmation);
            return invocation.callRealMethod();
        }).when(orderRepository).confirmIfDraft(eq(fixture.orderId()), eq(fixture.userId()), eq(100L), any());

        try {
            Future<?> confirmation = locks.submit(workers,
                () -> orderFacade.confirm(fixture.userId(), fixture.orderId()));
            assertThat(confirmationLocked.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            Future<?> deletion = locks.submit(workers, deletionConnection, deletionStarted,
                () -> brandFacade.delete(fixture.brandId()));
            assertThat(deletionStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            locks.awaitLockWait(deletionConnection.get(), "brands");

            allowConfirmation.countDown();
            confirmation.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            deletion.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertFinalState(fixture, true, OrderStatus.CONFIRMED, 4L, 900L);
        } finally {
            allowConfirmation.countDown();
            locks.shutDown(workers);
        }
    }

    @Test
    @DisplayName("삭제의 실제 상품 변경이 롤백되면 대기하던 확정은 활성 Brand로 계속 진행한다")
    void confirmationContinuesAfterDeletionRollsBack() throws Exception {
        Fixture fixture = createFixture();
        CountDownLatch productsDeleted = new CountDownLatch(1);
        CountDownLatch allowFailure = new CountDownLatch(1);
        CountDownLatch confirmationStarted = new CountDownLatch(1);
        AtomicLong confirmationConnection = new AtomicLong();
        ExecutorService workers = Executors.newFixedThreadPool(2);

        doAnswer(invocation -> {
            productsDeleted.countDown();
            locks.awaitLatch(allowFailure);
            throw new IllegalStateException("Brand 저장 경계에서 실패 주입");
        }).when(brandRepository).softDeleteActiveById(eq(fixture.brandId()), any(ZonedDateTime.class));

        try {
            Future<?> deletion = locks.submit(workers, () -> brandFacade.delete(fixture.brandId()));
            assertThat(productsDeleted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            Future<?> confirmation = locks.submit(workers, confirmationConnection, confirmationStarted,
                () -> orderFacade.confirm(fixture.userId(), fixture.orderId()));
            assertThat(confirmationStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            locks.awaitLockWait(confirmationConnection.get(), "brands");

            allowFailure.countDown();
            assertThatThrownBy(() -> deletion.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class).cause()
                .isInstanceOf(IllegalStateException.class).hasMessage("Brand 저장 경계에서 실패 주입");
            confirmation.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertFinalState(fixture, false, OrderStatus.CONFIRMED, 4L, 900L);
        } finally {
            allowFailure.countDown();
            locks.shutDown(workers);
        }
    }

    private Fixture createFixture() {
        User user = userRepository.save(User.create());
        pointRepository.save(Point.create(user.getId(), new PointBalance(1_000L)));
        Brand brand = brandRepository.save(Brand.create("Nike"));
        Product product = saveProduct(brand.getId(), "First");
        Order order = orderRepository.save(Order.create(user.getId(), List.of(
            OrderItem.create(product.getId(), product.getName(), product.getPrice(), 1)
        )));
        return new Fixture(brand.getId(), product.getId(), user.getId(), order.getId());
    }

    private Product saveProduct(Long brandId, String name) {
        Product product = Product.create(brandId, name, 100L);
        product.changeStockTo(5L);
        return productRepository.save(product);
    }

    private void assertFinalState(Fixture fixture, boolean deleted, OrderStatus orderStatus, long stock, long balance) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            Brand brand = brandRepository.findById(fixture.brandId()).orElseThrow();
            Product product = productRepository.findById(fixture.productId()).orElseThrow();
            Order order = orderRepository.findById(fixture.orderId()).orElseThrow();
            assertThat(brand.getDeletedAt() != null).isEqualTo(deleted);
            assertThat(product.getDeletedAt()).isEqualTo(brand.getDeletedAt());
            assertThat(product.getStock().amount()).isEqualTo(stock);
            assertThat(pointRepository.findByUserId(fixture.userId()).orElseThrow().getBalance().amount())
                .isEqualTo(balance);
            assertThat(order.getStatus()).isEqualTo(orderStatus);
            assertThat(order.getTotalAmount()).isEqualTo(100L);
            assertThat(order.getItems()).singleElement().satisfies(item -> {
                assertThat(item.getProductId()).isEqualTo(fixture.productId());
                assertThat(item.getQuantity()).isEqualTo(1);
                assertThat(item.getUnitPrice()).isEqualTo(100L);
            });
            if (orderStatus == OrderStatus.CONFIRMED) {
                assertThat(order.getPaymentAmount()).isEqualTo(100L);
                assertThat(order.getPaymentResult().name()).isEqualTo("SUCCESS");
            } else {
                assertThat(order.getPaymentAmount()).isNull();
                assertThat(order.getPaymentResult()).isNull();
            }
        });
    }

    private record Fixture(Long brandId, Long productId, Long userId, Long orderId) {}
}
