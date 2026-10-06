package com.loopers.application.order;

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
import com.loopers.application.point.PointFacade;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class OrderPointConcurrencyIntegrationTest {

    private static final int ORDER_COUNT = 3;
    private static final long INITIAL_POINT_BALANCE = 10_000L;
    private static final long ORDER_AMOUNT = 4_000L;
    private static final long INITIAL_STOCK = 5L;
    private static final long TIMEOUT_SECONDS = 20L;

    @Autowired
    private OrderFacade orderFacade;

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

    @PersistenceContext
    private EntityManager entityManager;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @Test
    void confirmsOnlyOrdersCoveredByTheSharedPointBalance() throws Exception {
        Competition competition = createCompetition();
        CountDownLatch workersReady = new CountDownLatch(ORDER_COUNT);
        CountDownLatch startWorkers = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(ORDER_COUNT);

        try {
            List<Future<ConfirmationResult>> attempts = competition.orders().stream()
                .map(order -> submitConfirmation(workers, order, workersReady, startWorkers))
                .toList();

            assertThat(workersReady.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            startWorkers.countDown();

            List<ConfirmationResult> results = new ArrayList<>();
            for (Future<ConfirmationResult> attempt : attempts) {
                results.add(attempt.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            }

            assertThat(results).containsExactlyInAnyOrder(
                ConfirmationResult.CONFIRMED,
                ConfirmationResult.CONFIRMED,
                ConfirmationResult.INSUFFICIENT_BALANCE
            );

            entityManager.clear();
            Point savedPoint = pointRepository.findByUserId(competition.userId()).orElseThrow();
            assertThat(savedPoint.getBalance().amount()).isEqualTo(2_000L);
            assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM orders WHERE status = 'CONFIRMED'",
                Long.class
            )).isEqualTo(2L);
            assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM orders WHERE status = 'DRAFT'",
                Long.class
            )).isEqualTo(1L);
            assertThat(jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(payment_amount), 0) FROM orders WHERE status = 'CONFIRMED'",
                Long.class
            )).isEqualTo(2L * ORDER_AMOUNT);

            for (OrderFixture orderFixture : competition.orders()) {
                Order savedOrder = orderRepository.findById(orderFixture.orderId()).orElseThrow();
                Product savedProduct = productRepository.findById(orderFixture.productId()).orElseThrow();
                assertThat(savedOrder.getItems()).singleElement().satisfies(item -> {
                    assertThat(item.getProductId()).isEqualTo(orderFixture.productId());
                    assertThat(item.getQuantity()).isEqualTo(1);
                    assertThat(item.getAmount()).isEqualTo(ORDER_AMOUNT);
                });
                if (savedOrder.getStatus() == OrderStatus.CONFIRMED) {
                    assertThat(savedOrder.getPaymentAmount()).isEqualTo(ORDER_AMOUNT);
                    assertThat(savedOrder.getPaymentResult().name()).isEqualTo("SUCCESS");
                    assertThat(savedProduct.getStock().amount()).isEqualTo(INITIAL_STOCK - 1L);
                } else {
                    assertThat(savedOrder.getStatus()).isEqualTo(OrderStatus.DRAFT);
                    assertThat(savedOrder.getPaymentAmount()).isNull();
                    assertThat(savedOrder.getPaymentResult()).isNull();
                    assertThat(savedProduct.getStock().amount()).isEqualTo(INITIAL_STOCK);
                }
            }
        } finally {
            startWorkers.countDown();
            workers.shutdownNow();
            assertThat(workers.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void preservesConcurrentChargeAndOrderConfirmation() throws Exception {
        OrderFixture order = createChargeAndConfirmationFixture();
        CountDownLatch workersReady = new CountDownLatch(2);
        CountDownLatch startWorkers = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);

        try {
            Future<?> chargeAttempt = workers.submit(() -> {
                awaitStart(workersReady, startWorkers);
                return pointFacade.charge(order.userId(), 2_000L);
            });
            Future<?> confirmationAttempt = workers.submit(() -> {
                awaitStart(workersReady, startWorkers);
                return orderFacade.confirm(order.userId(), order.orderId());
            });

            assertThat(workersReady.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            startWorkers.countDown();
            chargeAttempt.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            confirmationAttempt.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            entityManager.clear();
            Point savedPoint = pointRepository.findByUserId(order.userId()).orElseThrow();
            Order savedOrder = orderRepository.findById(order.orderId()).orElseThrow();
            Product savedProduct = productRepository.findById(order.productId()).orElseThrow();

            assertThat(savedPoint.getBalance().amount()).isEqualTo(5_000L);
            assertThat(savedOrder.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(savedOrder.getPaymentAmount()).isEqualTo(7_000L);
            assertThat(savedProduct.getStock().amount()).isEqualTo(INITIAL_STOCK - 1L);
        } finally {
            startWorkers.countDown();
            workers.shutdownNow();
            assertThat(workers.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void confirmsTheSameDraftOrderOnlyOnce() throws Exception {
        OrderFixture order = createSameOrderCompetitionFixture();
        CountDownLatch workersReady = new CountDownLatch(2);
        CountDownLatch startWorkers = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);

        try {
            Future<SameOrderResult> firstAttempt = submitSameOrderConfirmation(
                workers, order, workersReady, startWorkers
            );
            Future<SameOrderResult> secondAttempt = submitSameOrderConfirmation(
                workers, order, workersReady, startWorkers
            );

            assertThat(workersReady.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            startWorkers.countDown();
            assertThat(List.of(
                firstAttempt.get(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                secondAttempt.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            )).containsExactlyInAnyOrder(SameOrderResult.CONFIRMED, SameOrderResult.ALREADY_CONFIRMED);

            entityManager.clear();
            Point savedPoint = pointRepository.findByUserId(order.userId()).orElseThrow();
            Order savedOrder = orderRepository.findById(order.orderId()).orElseThrow();
            Product savedProduct = productRepository.findById(order.productId()).orElseThrow();

            assertThat(savedOrder.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(savedOrder.getPaymentAmount()).isEqualTo(4_000L);
            assertThat(savedPoint.getBalance().amount()).isEqualTo(6_000L);
            assertThat(savedProduct.getStock().amount()).isEqualTo(INITIAL_STOCK - 1L);
        } finally {
            startWorkers.countDown();
            workers.shutdownNow();
            assertThat(workers.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        }
    }

    private OrderFixture createSameOrderCompetitionFixture() {
        User user = userRepository.save(User.create());
        pointRepository.save(Point.create(user.getId(), new PointBalance(INITIAL_POINT_BALANCE)));
        Brand brand = brandRepository.save(Brand.create("Nike"));
        Product product = Product.create(brand.getId(), "Same order product", 4_000L);
        product.changeStockTo(INITIAL_STOCK);
        product = productRepository.save(product);
        Order order = orderRepository.save(Order.create(user.getId(), List.of(
            OrderItem.create(product.getId(), product.getName(), product.getPrice(), 1)
        )));
        return new OrderFixture(user.getId(), product.getId(), order.getId());
    }

    private Future<SameOrderResult> submitSameOrderConfirmation(
        ExecutorService workers,
        OrderFixture order,
        CountDownLatch workersReady,
        CountDownLatch startWorkers
    ) {
        return workers.submit(() -> {
            awaitStart(workersReady, startWorkers);
            try {
                orderFacade.confirm(order.userId(), order.orderId());
                return SameOrderResult.CONFIRMED;
            } catch (CoreException exception) {
                if (exception.getErrorType() == ErrorType.CONFLICT
                    && "DRAFT 주문만 확정할 수 있습니다.".equals(exception.getCustomMessage())) {
                    return SameOrderResult.ALREADY_CONFIRMED;
                }
                throw exception;
            }
        });
    }

    private OrderFixture createChargeAndConfirmationFixture() {
        User user = userRepository.save(User.create());
        pointRepository.save(Point.create(user.getId(), new PointBalance(INITIAL_POINT_BALANCE)));
        Brand brand = brandRepository.save(Brand.create("Nike"));
        Product product = Product.create(brand.getId(), "Charge race product", 7_000L);
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
                throw new AssertionError("Point 경쟁 worker 시작 대기 시간이 초과됐습니다.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Point 경쟁 worker가 중단됐습니다.", exception);
        }
    }

    private Competition createCompetition() {
        User user = userRepository.save(User.create());
        pointRepository.save(Point.create(user.getId(), new PointBalance(INITIAL_POINT_BALANCE)));
        Brand brand = brandRepository.save(Brand.create("Nike"));
        List<OrderFixture> orders = new ArrayList<>();

        for (int index = 0; index < ORDER_COUNT; index++) {
            Product product = Product.create(brand.getId(), "Product " + index, ORDER_AMOUNT);
            product.changeStockTo(INITIAL_STOCK);
            product = productRepository.save(product);
            Order order = orderRepository.save(Order.create(user.getId(), List.of(
                OrderItem.create(product.getId(), product.getName(), product.getPrice(), 1)
            )));
            orders.add(new OrderFixture(user.getId(), product.getId(), order.getId()));
        }

        return new Competition(user.getId(), List.copyOf(orders));
    }

    private Future<ConfirmationResult> submitConfirmation(
        ExecutorService workers,
        OrderFixture order,
        CountDownLatch workersReady,
        CountDownLatch startWorkers
    ) {
        return workers.submit(() -> {
            workersReady.countDown();
            if (!startWorkers.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new AssertionError("주문 확정 worker 시작 대기 시간이 초과됐습니다.");
            }
            try {
                orderFacade.confirm(order.userId(), order.orderId());
                return ConfirmationResult.CONFIRMED;
            } catch (CoreException exception) {
                if (exception.getErrorType() == ErrorType.CONFLICT
                    && "포인트 잔액이 부족합니다.".equals(exception.getCustomMessage())) {
                    return ConfirmationResult.INSUFFICIENT_BALANCE;
                }
                throw exception;
            }
        });
    }

    private record Competition(Long userId, List<OrderFixture> orders) {}

    private record OrderFixture(Long userId, Long productId, Long orderId) {}

    private enum ConfirmationResult {
        CONFIRMED,
        INSUFFICIENT_BALANCE
    }

    private enum SameOrderResult {
        CONFIRMED,
        ALREADY_CONFIRMED
    }
}
