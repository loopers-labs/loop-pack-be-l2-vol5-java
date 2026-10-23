package com.loopers.application.order;

import com.loopers.application.payment.PaymentRequestFacade;
import com.loopers.application.point.PointFacade;
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
import com.loopers.support.error.ErrorType;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

// 결제 요청은 포인트를 쓰지 않으며, 같은 주문의 결제 시작은 한 번만 성공한다.
@SpringBootTest
class OrderPaymentConcurrencyIntegrationTest {

    private static final long INITIAL_STOCK = 5L;
    private static final long INITIAL_POINT_BALANCE = 3_000L;
    private static final long TIMEOUT_SECONDS = 20L;

    @Autowired
    private PaymentRequestFacade paymentRequestFacade;

    @Autowired
    private PointFacade pointFacade;

    @Autowired
    private BrandRepository brandRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private PointRepository pointRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("충전과 결제 요청이 동시에 실행돼도 충전액은 모두 반영되고 결제 요청은 포인트를 바꾸지 않는다.")
    @Test
    void keepsConcurrentChargeWhilePaymentIsRequested() throws Exception {
        OrderFixture order = createFixture(7_000L);
        CountDownLatch workersReady = new CountDownLatch(2);
        CountDownLatch startWorkers = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);

        try {
            Future<?> chargeAttempt = workers.submit(() -> {
                awaitStart(workersReady, startWorkers);
                return pointFacade.charge(order.userId(), 2_000L);
            });
            Future<?> paymentAttempt = workers.submit(() -> {
                awaitStart(workersReady, startWorkers);
                return requestPayment(order, UUID.randomUUID().toString());
            });

            assertThat(workersReady.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            startWorkers.countDown();
            List<ConcurrentRequestResults.Result> results = ConcurrentRequestResults.collect(List.of(
                new ConcurrentRequestResults.Attempt("point-charge:" + order.userId(), chargeAttempt),
                new ConcurrentRequestResults.Attempt("order:" + order.orderId(), paymentAttempt)
            ), TIMEOUT_SECONDS);
            ConcurrentRequestResults.assertExpectedCounts(results, 2L, 0L, 2L, exception -> false);

            Order savedOrder = orderRepository.findById(order.orderId()).orElseThrow();
            assertThat(pointRepository.findByUserId(order.userId()).orElseThrow().getBalance().amount())
                .isEqualTo(INITIAL_POINT_BALANCE + 2_000L);
            assertThat(savedOrder.getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
            assertThat(savedOrder.getPaymentAmount()).isNull();
            assertThat(productRepository.findById(order.productId()).orElseThrow().getStock().amount())
                .isEqualTo(INITIAL_STOCK - 1L);
        } finally {
            startWorkers.countDown();
            workers.shutdownNow();
            assertThat(workers.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        }
    }

    @DisplayName("같은 주문에 서로 다른 키로 동시에 결제를 요청하면 한 건만 시작하고 나머지는 409이며 재고는 한 번만 차감한다.")
    @Test
    void startsSameOrderOnlyOnceForDifferentKeys() throws Exception {
        OrderFixture order = createFixture(4_000L);
        CountDownLatch workersReady = new CountDownLatch(2);
        CountDownLatch startWorkers = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);

        try {
            Future<?> firstAttempt = workers.submit(() -> {
                awaitStart(workersReady, startWorkers);
                return requestPayment(order, UUID.randomUUID().toString());
            });
            Future<?> secondAttempt = workers.submit(() -> {
                awaitStart(workersReady, startWorkers);
                return requestPayment(order, UUID.randomUUID().toString());
            });

            assertThat(workersReady.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            startWorkers.countDown();
            List<ConcurrentRequestResults.Result> results = ConcurrentRequestResults.collect(List.of(
                new ConcurrentRequestResults.Attempt("order:" + order.orderId() + ":first", firstAttempt),
                new ConcurrentRequestResults.Attempt("order:" + order.orderId() + ":second", secondAttempt)
            ), TIMEOUT_SECONDS);
            ConcurrentRequestResults.assertExpectedCounts(
                results, 1L, 1L, 2L, exception -> exception.getErrorType() == ErrorType.CONFLICT
            );

            assertThat(orderRepository.findById(order.orderId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PAYMENT_PENDING);
            assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_attempts WHERE order_id = ?", Long.class, order.orderId())).isEqualTo(1L);
            assertThat(pointRepository.findByUserId(order.userId()).orElseThrow().getBalance().amount())
                .isEqualTo(INITIAL_POINT_BALANCE);
            assertThat(productRepository.findById(order.productId()).orElseThrow().getStock().amount())
                .isEqualTo(INITIAL_STOCK - 1L);
        } finally {
            startWorkers.countDown();
            workers.shutdownNow();
            assertThat(workers.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        }
    }

    private Object requestPayment(OrderFixture order, String key) {
        return paymentRequestFacade.requestPayment(order.userId(), order.orderId(), key, "TEST", "0000-0000-0000-0000");
    }

    private OrderFixture createFixture(long price) {
        User user = userRepository.save(User.create());
        pointRepository.save(Point.create(user.getId(), new PointBalance(INITIAL_POINT_BALANCE)));
        Brand brand = brandRepository.save(Brand.create("Nike"));
        Product product = Product.create(brand.getId(), "Race product", price);
        product.changeStockTo(INITIAL_STOCK);
        product = productRepository.save(product);
        Order order = orderRepository.save(Order.create(user.getId(), List.of(
            OrderItem.create(product.getId(), product.getName(), product.getPrice(), 1)
        )));
        return new OrderFixture(user.getId(), product.getId(), order.getId());
    }

    private void awaitStart(CountDownLatch workersReady, CountDownLatch startWorkers) {
        workersReady.countDown();
        try {
            if (!startWorkers.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new AssertionError("결제 요청 경쟁 worker 시작 대기 시간이 초과됐습니다.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("결제 요청 경쟁 worker가 중단됐습니다.", exception);
        }
    }

    private record OrderFixture(Long userId, Long productId, Long orderId) {}
}
