package com.loopers.application.payment;

import com.loopers.application.brand.BrandLockTestSupport;
import com.loopers.application.product.ProductFacade;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderRepository;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.payment.PaymentAttemptRepository;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.user.User;
import com.loopers.domain.user.UserRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.support.testpg.TestPgControl;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import org.mockito.invocation.InvocationOnMock;

import javax.sql.DataSource;
import java.util.List;
import java.util.UUID;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// 결제 요청의 차감·실패 반환과 관리자 재고 설정이 같은 Product 행에서 만날 때의 기대값을 확인한다.
// 기준: 초기 재고 10, 주문 수량 2. 결과는 모든 트랜잭션이 끝난 뒤 새 조회로 확인한다.
@SpringBootTest
class PaymentStockAdminInterplayIntegrationTest {

    private static final long INITIAL_STOCK = 10L;
    private static final int QUANTITY = 2;

    @Autowired
    private PaymentRequestFacade paymentRequestFacade;

    @Autowired
    private PaymentResultService paymentResultService;

    @Autowired
    private ProductFacade productFacade;

    @MockitoBean
    private ProviderOrderSuffixGenerator suffixGenerator;

    @MockitoSpyBean
    private ProductRepository productRepository;

    @MockitoSpyBean
    private OrderRepository orderRepository;

    @MockitoSpyBean
    private PaymentGateway paymentGateway;

    @Autowired
    private PaymentAttemptRepository paymentAttemptRepository;

    @Autowired
    private BrandRepository brandRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private BrandLockTestSupport locks;
    private String suffix;
    private Fixture fixture;

    @BeforeEach
    void setUp() {
        locks = new BrandLockTestSupport(dataSource, jdbcTemplate, transactionManager);
        suffix = UUID.randomUUID().toString().substring(0, 8);
        when(suffixGenerator.next()).thenAnswer(invocation -> suffix);
        fixture = createFixture();
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("상위 TX의 오래된 스냅샷으로 재고를 검사하지 않도록 바깥 TX 호출을 거절한다.")
    @Test
    void rejectsOuterTransactionForStockSetting() {
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(tx ->
            productFacade.changeStock(fixture.productId(), 5L)))
            .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(stock()).isEqualTo(INITIAL_STOCK);
    }

    @Nested
    class Sequential {

        @DisplayName("DRAFT는 제외하고 같은 상품의 모든 미완료 주문 예약을 합산하며 각 실패를 한 번 반환한다.")
        @Test
        void accountsForAllPendingOrdersWithoutCountingDraft() {
            failInPgLater();
            request();
            Order second = orderRepository.save(Order.create(fixture.userId(), List.of(
                OrderItem.create(fixture.productId(), "Air Max", 1_000L, 3))));
            productFacade.changeStock(fixture.productId(), Long.MAX_VALUE - QUANTITY);
            String secondProviderOrderId = "LP-ORD-%010d-%s".formatted(second.getId(), suffix);
            TestPgControl.scenario(secondProviderOrderId, "{\"scenario\":\"FAIL\"}");
            paymentRequestFacade.requestPayment(fixture.userId(), second.getId(), UUID.randomUUID().toString(),
                "TEST", "0000-0000-0000-0000");

            assertThatThrownBy(() -> productFacade.changeStock(fixture.productId(), Long.MAX_VALUE - 4))
                .isInstanceOfSatisfying(CoreException.class, exception ->
                    assertThat(exception.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST));
            productFacade.changeStock(fixture.productId(), Long.MAX_VALUE - 5);
            finishInPg();
            TestPgControl.complete(secondProviderOrderId);
            Long secondAttemptId = paymentAttemptRepository.findByOrderId(second.getId()).orElseThrow().getId();
            paymentResultService.check(secondAttemptId);
            paymentResultService.check(secondAttemptId);

            assertThat(stock()).isEqualTo(Long.MAX_VALUE);
            assertThat(orderStatus()).isEqualTo(OrderStatus.PAYMENT_FAILED);
            assertThat(orderRepository.findById(second.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.PAYMENT_FAILED);
        }

        @DisplayName("예약 중 범위 초과 설정은 400으로 값이 유지되고, 경계값 설정 뒤 실패 반환은 최댓값까지 성공한다.")
        @Test
        void protectsCapacityForFailedReservation() {
            failInPgLater();
            request();

            assertThatThrownBy(() -> productFacade.changeStock(fixture.productId(), Long.MAX_VALUE - 1))
                .isInstanceOfSatisfying(CoreException.class, exception ->
                    assertThat(exception.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST));
            assertThat(stock()).isEqualTo(8L);
            productFacade.changeStock(fixture.productId(), Long.MAX_VALUE - QUANTITY);

            finishInPg();
            paymentResultService.check(attemptId());

            assertThat(stock()).isEqualTo(Long.MAX_VALUE);
            assertThat(orderStatus()).isEqualTo(OrderStatus.PAYMENT_FAILED);
        }

        @DisplayName("예약이 없거나 성공으로 소비된 뒤에는 관리자 재고에 long 최댓값을 설정할 수 있다.")
        @Test
        void keepsFullRangeWithoutReservations() {
            productFacade.changeStock(fixture.productId(), Long.MAX_VALUE);
            request();
            finishInPg();

            productFacade.changeStock(fixture.productId(), Long.MAX_VALUE);

            assertThat(stock()).isEqualTo(Long.MAX_VALUE);
            assertThat(orderStatus()).isEqualTo(OrderStatus.CONFIRMED);
        }

        @DisplayName("결제 요청 → 관리자 5 설정 → 성공: 10 → 8 → 5 → 5, 성공은 재고를 다시 차감하지 않는다.")
        @Test
        void successKeepsAdminStock() {
            request();
            assertThat(stock()).isEqualTo(8L);
            productFacade.changeStock(fixture.productId(), 5L);

            finishInPg();

            assertThat(stock()).isEqualTo(5L);
            assertThat(orderStatus()).isEqualTo(OrderStatus.CONFIRMED);
        }

        @DisplayName("결제 요청 → 관리자 5 설정 → 실패: 10 → 8 → 5 → 7, 관리자 설정 뒤에도 품목 수량 2를 반환한다.")
        @Test
        void failureReturnsQuantityOnAdminStock() {
            failInPgLater();
            request();
            productFacade.changeStock(fixture.productId(), 5L);

            finishInPg();

            assertThat(stock()).isEqualTo(7L);
            assertThat(orderStatus()).isEqualTo(OrderStatus.PAYMENT_FAILED);
        }

        @DisplayName("관리자 5 설정 → 결제 요청: 10 → 5 → 3, 설정값이 가용 재고다.")
        @Test
        void requestDecreasesFromAdminStock() {
            productFacade.changeStock(fixture.productId(), 5L);

            request();

            assertThat(stock()).isEqualTo(3L);
        }

        @DisplayName("결제 요청 → 관리자 0 설정 → 실패: 10 → 8 → 0 → 2, 반환한 수량은 다시 가용 재고가 된다.")
        @Test
        void failureReturnsQuantityOnZeroStock() {
            failInPgLater();
            request();
            productFacade.changeStock(fixture.productId(), 0L);

            finishInPg();

            assertThat(stock()).isEqualTo(2L);
        }

        @DisplayName("결제 요청 → 관리자 5 설정 → 상품 삭제 → 실패: 8 → 5 → 5 → 7, 삭제 상태를 유지하고 삭제 뒤 설정은 404다.")
        @Test
        void failureReturnsQuantityToDeletedProduct() {
            failInPgLater();
            request();
            productFacade.changeStock(fixture.productId(), 5L);
            productFacade.delete(fixture.productId());

            finishInPg();

            assertThat(stock()).isEqualTo(7L);
            assertThat(productRepository.findById(fixture.productId()).orElseThrow().getDeletedAt()).isNotNull();
            assertThatThrownBy(() -> productFacade.changeStock(fixture.productId(), 5L))
                .isInstanceOfSatisfying(CoreException.class, exception ->
                    assertThat(exception.getErrorType()).isEqualTo(ErrorType.NOT_FOUND));
        }
    }

    @Nested
    class Overlapping {
        private final ExecutorService workers = Executors.newFixedThreadPool(2);

        @DisplayName("예약 차감을 기다린 관리자 설정은 commit된 예약 합을 읽어 범위 초과를 400으로 거절한다.")
        @Test
        void validatesLatestReservationAfterLockWait() throws Exception {
            Pause decrease = pauseAfterDecrease();
            Future<?> admin = runOverlap(
                () -> locks.submitOutsideTransaction(workers, PaymentStockAdminInterplayIntegrationTest.this::request),
                decrease, () -> adminSetWaitingForProducts(Long.MAX_VALUE));

            assertThatThrownBy(() -> admin.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class).cause()
                .isInstanceOfSatisfying(CoreException.class, exception ->
                    assertThat(exception.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST));
            assertThat(stock()).isEqualTo(8L);
            assertThat(orderStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        }

        @DisplayName("결제 요청 차감 2가 먼저 행 잠금을 얻으면 관리자 5 설정은 기다린 뒤 반영된다: 10 → 8 → 5.")
        @Test
        void adminWaitsForDecrease() throws Exception {
            Pause decrease = pauseAfterDecrease();

            runOverlap(() -> locks.submitOutsideTransaction(workers, PaymentStockAdminInterplayIntegrationTest.this::request),
                decrease, () -> adminSetWaitingForProducts(5L));

            assertThat(stock()).isEqualTo(5L);
            assertThat(orderStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        }

        @DisplayName("관리자 5 설정이 먼저 행 잠금을 얻으면 결제 요청 차감은 기다린 뒤 그 값에서 차감한다: 10 → 5 → 3.")
        @Test
        void decreaseWaitsForAdmin() throws Exception {
            Pause admin = pauseAfterAdminUpdate(5L);

            runOverlap(() -> locks.submitOutsideTransaction(workers, () -> productFacade.changeStock(fixture.productId(), 5L)),
                admin, this::requestWaitingForProducts);

            assertThat(stock()).isEqualTo(3L);
            assertThat(orderStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        }

        @DisplayName("관리자 1 설정이 먼저 반영되면 결제 요청은 재고 부족 409로 TX A 전체를 되돌리고 PG를 호출하지 않는다.")
        @Test
        void decreaseIsRejectedAfterAdminSetsOne() throws Exception {
            Pause admin = pauseAfterAdminUpdate(1L);

            Future<?> request = runOverlap(
                () -> locks.submitOutsideTransaction(workers, () -> productFacade.changeStock(fixture.productId(), 1L)),
                admin, this::requestWaitingForProducts);

            assertThatThrownBy(() -> request.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class).cause()
                .isInstanceOfSatisfying(CoreException.class, exception ->
                    assertThat(exception.getErrorType()).isEqualTo(ErrorType.CONFLICT));
            assertThat(stock()).isEqualTo(1L);
            assertThat(orderStatus()).isEqualTo(OrderStatus.DRAFT);
            assertThat(paymentAttemptRepository.findByOrderId(fixture.orderId())).isEmpty();
            verify(paymentGateway, never()).create(any());
        }

        @DisplayName("실패 반환 2가 먼저 행 잠금을 얻으면 관리자 5 설정이 최종 값이다: 8 → 10 → 5.")
        @Test
        void adminWaitsForReturn() throws Exception {
            failInPgLater();
            request();
            TestPgControl.complete(providerOrderId());
            Pause stockReturn = pauseAfterReturn();

            runOverlap(() -> locks.submitOutsideTransaction(workers, () -> paymentResultService.check(attemptId())),
                stockReturn, () -> adminSetWaitingForProducts(5L));

            assertThat(stock()).isEqualTo(5L);
            assertThat(orderStatus()).isEqualTo(OrderStatus.PAYMENT_FAILED);
        }

        @DisplayName("관리자 5 설정이 먼저 행 잠금을 얻으면 실패 반환은 기다린 뒤 그 값에 더한다: 8 → 5 → 7.")
        @Test
        void returnWaitsForAdmin() throws Exception {
            failInPgLater();
            request();
            TestPgControl.complete(providerOrderId());
            Pause admin = pauseAfterAdminUpdate(5L);

            runOverlap(() -> locks.submitOutsideTransaction(workers, () -> productFacade.changeStock(fixture.productId(), 5L)),
                admin, this::checkWaitingForProducts);

            assertThat(stock()).isEqualTo(7L);
            assertThat(orderStatus()).isEqualTo(OrderStatus.PAYMENT_FAILED);
        }

        // 먼저 잠금을 얻을 작업을 시작해 Product UPDATE 직후 멈춘다.
        // 두 번째 작업이 products 행에서 실제로 기다리는지 확인한 뒤 첫 작업을 풀어 준다.
        private Future<?> runOverlap(FutureSupplier first, Pause pause, SecondOperation second) throws Exception {
            try {
                Future<?> firstFuture = first.get();
                assertThat(pause.locked().await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
                Future<?> secondFuture = second.startAndAwaitLockWait();
                pause.release().countDown();
                firstFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                try {
                    secondFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                } catch (ExecutionException ignored) {
                    // 거절되는 경우는 호출한 테스트가 결과를 다시 확인한다.
                }
                return secondFuture;
            } finally {
                pause.release().countDown();
                locks.shutDown(workers);
            }
        }

        private Pause pauseAfterDecrease() {
            Pause pause = new Pause();
            doAnswer(pause::proceedThenHold)
                .when(productRepository).decreaseActiveStock(eq(fixture.productId()), eq(QUANTITY), any());
            return pause;
        }

        private Pause pauseAfterReturn() {
            Pause pause = new Pause();
            doAnswer(pause::proceedThenHold)
                .when(productRepository).increaseStock(eq(fixture.productId()), eq(QUANTITY), any());
            return pause;
        }

        private Pause pauseAfterAdminUpdate(long stock) {
            Pause pause = new Pause();
            doAnswer(pause::proceedThenHold)
                .when(productRepository).updateActiveStock(eq(fixture.productId()), eq(stock), any());
            return pause;
        }

        private Future<?> adminSetWaitingForProducts(long stock) throws Exception {
            AtomicLong connection = new AtomicLong();
            CountDownLatch started = new CountDownLatch(1);
            doAnswer(invocation -> {
                locks.recordConnection(connection, started);
                return invocation.callRealMethod();
            }).when(productRepository).findByIdWithExclusiveLock(fixture.productId());
            Future<?> admin = locks.submitOutsideTransaction(workers,
                () -> productFacade.changeStock(fixture.productId(), stock));
            assertThat(started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            locks.awaitLockWait(connection.get(), "products");
            return admin;
        }

        // 결제 요청은 TX A를 스스로 연다. TX A의 첫 조회(주문)에서 connection을 기록해 products 행 대기를 관찰한다.
        private Future<?> requestWaitingForProducts() throws Exception {
            return startWaitingForProducts(PaymentStockAdminInterplayIntegrationTest.this::request);
        }

        // 결과 반영 TX B는 시도를 읽은 뒤 주문을 읽는다. 그 주문 조회에서 TX B의 connection을 기록한다.
        private Future<?> checkWaitingForProducts() throws Exception {
            return startWaitingForProducts(() -> paymentResultService.check(attemptId()));
        }

        private Future<?> startWaitingForProducts(Runnable operation) throws Exception {
            AtomicLong connection = new AtomicLong();
            CountDownLatch started = new CountDownLatch(1);
            doAnswer(invocation -> {
                if (started.getCount() > 0) {
                    locks.recordConnection(connection, started);
                }
                return invocation.callRealMethod();
            }).when(orderRepository).findById(fixture.orderId());
            Future<?> future = locks.submitOutsideTransaction(workers, operation);
            assertThat(started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            locks.awaitLockWait(connection.get(), "products");
            return future;
        }

        // 실제 UPDATE를 실행해 행 잠금을 얻은 뒤, 풀어 줄 때까지 트랜잭션을 끝내지 않는다.
        private final class Pause {
            private final CountDownLatch locked = new CountDownLatch(1);
            private final CountDownLatch release = new CountDownLatch(1);

            CountDownLatch locked() {
                return locked;
            }

            CountDownLatch release() {
                return release;
            }

            Object proceedThenHold(InvocationOnMock invocation) throws Throwable {
                Object rows = invocation.callRealMethod();
                locked.countDown();
                locks.awaitLatch(release);
                return rows;
            }
        }
    }

    @FunctionalInterface
    private interface FutureSupplier {
        Future<?> get() throws Exception;
    }

    @FunctionalInterface
    private interface SecondOperation {
        Future<?> startAndAwaitLockWait() throws Exception;
    }

    private void request() {
        paymentRequestFacade.requestPayment(fixture.userId(), fixture.orderId(), UUID.randomUUID().toString(),
            "TEST", "0000-0000-0000-0000");
    }

    private void failInPgLater() {
        TestPgControl.scenario(providerOrderId(), "{\"scenario\":\"FAIL\"}");
    }

    private void finishInPg() {
        TestPgControl.complete(providerOrderId());
        paymentResultService.check(attemptId());
    }

    private String providerOrderId() {
        return "LP-ORD-%010d-%s".formatted(fixture.orderId(), suffix);
    }

    private Long attemptId() {
        return paymentAttemptRepository.findByOrderId(fixture.orderId()).orElseThrow().getId();
    }

    private long stock() {
        return productRepository.findById(fixture.productId()).orElseThrow().getStock().amount();
    }

    private OrderStatus orderStatus() {
        return orderRepository.findById(fixture.orderId()).orElseThrow().getStatus();
    }

    private Fixture createFixture() {
        User user = userRepository.save(User.create());
        Brand brand = brandRepository.save(Brand.create("brand-" + UUID.randomUUID()));
        Product product = Product.create(brand.getId(), "Air Max", 1_000L);
        product.changeStockTo(INITIAL_STOCK);
        Product saved = productRepository.save(product);
        Order order = orderRepository.save(Order.create(user.getId(), List.of(
            OrderItem.create(saved.getId(), saved.getName(), saved.getPrice(), QUANTITY))));
        return new Fixture(user.getId(), saved.getId(), order.getId());
    }

    private record Fixture(Long userId, Long productId, Long orderId) {}
}
