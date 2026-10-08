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
class OrderInventoryConcurrencyIntegrationTest {

    private static final int ORDER_COUNT = 8;
    private static final int INITIAL_STOCK = 5;
    private static final long UNIT_PRICE = 100L;
    private static final long INITIAL_POINT_BALANCE = 1_000L;
    private static final long TIMEOUT_SECONDS = 20L;

    @Autowired
    private OrderFacade orderFacade;

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
    void confirmsOnlyAvailableQuantityWhenEightOrdersCompeteForFiveItems() throws Exception {
        Competition competition = createCompetition();
        CountDownLatch workersReady = new CountDownLatch(ORDER_COUNT);
        CountDownLatch startWorkers = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(ORDER_COUNT);

        try {
            List<ConcurrentRequestResults.Attempt> attempts = competition.orders().stream()
                .map(order -> new ConcurrentRequestResults.Attempt(
                    "order:" + order.orderId(),
                    submitConfirmation(workers, order, workersReady, startWorkers)
                ))
                .toList();

            assertThat(workersReady.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            startWorkers.countDown();

            List<ConcurrentRequestResults.Result> results = ConcurrentRequestResults.collect(
                attempts, TIMEOUT_SECONDS
            );
            ConcurrentRequestResults.assertExpectedCounts(
                results, 5L, 3L, ORDER_COUNT,
                exception -> exception.getErrorType() == ErrorType.CONFLICT
                    && "상품이 삭제되었거나 재고가 부족합니다.".equals(exception.getCustomMessage())
            );

            entityManager.clear();
            assertThat(jdbcTemplate.queryForObject(
                "SELECT stock FROM products WHERE id = ?",
                Long.class,
                competition.productId()
            )).isZero();
            assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM orders WHERE status = 'CONFIRMED'",
                Long.class
            )).isEqualTo(5L);
            assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM orders WHERE status = 'DRAFT'",
                Long.class
            )).isEqualTo(3L);
            assertThat(jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(payment_amount), 0) FROM orders WHERE status = 'CONFIRMED'",
                Long.class
            )).isEqualTo(5L * UNIT_PRICE);
            assertThat(jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(quantity), 0) FROM order_items oi "
                    + "JOIN orders o ON o.id = oi.order_id WHERE o.status = 'CONFIRMED'",
                Long.class
            )).isEqualTo(5L);

            for (OrderFixture order : competition.orders()) {
                Order persistedOrder = orderRepository.findById(order.orderId()).orElseThrow();
                Point persistedPoint = pointRepository.findByUserId(order.userId()).orElseThrow();
                ConcurrentRequestResults.Result result = results.stream()
                    .filter(request -> request.requestId().equals("order:" + order.orderId()))
                    .findFirst().orElseThrow();
                assertThat(persistedOrder.getStatus()).as("요청 결과와 주문 상태: %s", result.requestId())
                    .isEqualTo(result.outcome() == ConcurrentRequestResults.Outcome.SUCCESS
                        ? OrderStatus.CONFIRMED : OrderStatus.DRAFT);
                if (persistedOrder.getStatus() == OrderStatus.CONFIRMED) {
                    assertThat(persistedOrder.getPaymentAmount()).isEqualTo(UNIT_PRICE);
                    assertThat(persistedOrder.getPaymentResult().name()).isEqualTo("SUCCESS");
                    assertThat(persistedOrder.getItems()).singleElement().satisfies(item -> {
                        assertThat(item.getProductId()).isEqualTo(competition.productId());
                        assertThat(item.getQuantity()).isEqualTo(1);
                    });
                    assertThat(persistedPoint.getBalance().amount())
                        .isEqualTo(INITIAL_POINT_BALANCE - UNIT_PRICE);
                } else {
                    assertThat(persistedOrder.getStatus()).isEqualTo(OrderStatus.DRAFT);
                    assertThat(persistedPoint.getBalance().amount()).isEqualTo(INITIAL_POINT_BALANCE);
                    assertThat(persistedOrder.getPaymentAmount()).isNull();
                    assertThat(persistedOrder.getPaymentResult()).isNull();
                }
            }
        } finally {
            startWorkers.countDown();
            workers.shutdownNow();
            assertThat(workers.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        }
    }

    private Competition createCompetition() {
        Brand brand = brandRepository.save(Brand.create("Nike"));
        Product product = Product.create(brand.getId(), "Air Max", UNIT_PRICE);
        product.changeStockTo(INITIAL_STOCK);
        product = productRepository.save(product);

        List<OrderFixture> orders = new ArrayList<>();
        for (int index = 0; index < ORDER_COUNT; index++) {
            User user = userRepository.save(User.create());
            pointRepository.save(Point.create(user.getId(), new PointBalance(INITIAL_POINT_BALANCE)));
            Order order = orderRepository.save(Order.create(user.getId(), List.of(
                OrderItem.create(product.getId(), product.getName(), product.getPrice(), 1)
            )));
            orders.add(new OrderFixture(user.getId(), order.getId()));
        }

        return new Competition(product.getId(), List.copyOf(orders));
    }

    private Future<?> submitConfirmation(
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
            return orderFacade.confirm(order.userId(), order.orderId());
        });
    }

    private record Competition(Long productId, List<OrderFixture> orders) {}

    private record OrderFixture(Long userId, Long orderId) {}
}
