package com.loopers.application.order;

import com.loopers.domain.order.Order;
import com.loopers.domain.product.Price;
import com.loopers.domain.product.Product;
import com.loopers.domain.user.User;
import com.loopers.infrastructure.order.OrderJpaRepository;
import com.loopers.infrastructure.payment.FakePaymentGateway;
import com.loopers.infrastructure.payment.FakePaymentGatewayConfig;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.infrastructure.user.UserJpaRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 주문 확정(PG 결제 시작)의 동시 요청을 실제 MySQL 에서 검증한다.
 * 데이터는 worker 시작 전에 commit 되고, 각 worker 는 독립 트랜잭션으로 실제 Spring bean 을 호출한다.
 * 클래스 단위 @Transactional 을 쓰지 않는다.
 */
@SpringBootTest
@Import(FakePaymentGatewayConfig.class)
class OrderConcurrencyTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final String STOCK_REJECTED_MESSAGE = "재고가 부족합니다.";

    private final OrderFacade orderFacade;
    private final FakePaymentGateway fakePaymentGateway;
    private final UserJpaRepository userJpaRepository;
    private final ProductJpaRepository productJpaRepository;
    private final OrderJpaRepository orderJpaRepository;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final DatabaseCleanUp databaseCleanUp;

    @Autowired
    public OrderConcurrencyTest(
        OrderFacade orderFacade,
        FakePaymentGateway fakePaymentGateway,
        UserJpaRepository userJpaRepository,
        ProductJpaRepository productJpaRepository,
        OrderJpaRepository orderJpaRepository,
        JdbcTemplate jdbcTemplate,
        TransactionTemplate transactionTemplate,
        DatabaseCleanUp databaseCleanUp
    ) {
        this.orderFacade = orderFacade;
        this.fakePaymentGateway = fakePaymentGateway;
        this.userJpaRepository = userJpaRepository;
        this.productJpaRepository = productJpaRepository;
        this.orderJpaRepository = orderJpaRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.databaseCleanUp = databaseCleanUp;
    }

    @BeforeEach
    void setUp() {
        fakePaymentGateway.reset();
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    /**
     * 결과 분류. 재고 부족만 업무 거절로 세고, timeout·SQL 오류 등 나머지는 전부 기술 오류다.
     */
    enum Outcome {
        SUCCESS, STOCK_REJECTED, TECHNICAL
    }

    /**
     * @param amount 성공 시 주문 금액. 실패 시 0.
     * @param error  기술 오류의 원인. 거절·성공이면 null.
     */
    record Result(Outcome outcome, long amount, Throwable error) {}

    @DisplayName("[대조군] 조건 없는 read-modify-write 는, ")
    @Nested
    class LostUpdateControl {
        /**
         * 제품 코드가 아닌 순수 SQL 로 갱신 유실을 재현한다. 장벽(CyclicBarrier)은 이 대조군 안에서만 쓴다.
         * 두 독립 트랜잭션이 잠금 없는 SELECT 로 같은 재고 5 를 읽은 것을 확인한 뒤, 각각 조건·version 없이 4 를 저장한다.
         */
        @DisplayName("두 트랜잭션이 같은 재고를 읽고 각각 1 감소값을 저장하면, 둘 다 성공하지만 차감 1건이 유실된다.")
        @Test
        void losesOneDeduction_whenTwoTransactionsReadThenWriteWithoutCondition() throws Exception {
            // arrange
            Long productId = saveProduct("대조군 상품", 100L, 5);
            List<Integer> reads = new CopyOnWriteArrayList<>();
            CyclicBarrier bothRead = new CyclicBarrier(2, () -> {
                if (!reads.equals(List.of(5, 5))) {
                    throw new IllegalStateException("두 트랜잭션이 모두 5 를 읽어야 한다: " + reads);
                }
            });
            Callable<Long> readThenWrite = () -> transactionTemplate.execute(status -> {
                Integer read = jdbcTemplate.queryForObject(
                    "SELECT quantity FROM products WHERE id = ?", Integer.class, productId);
                reads.add(read);
                await(bothRead);
                jdbcTemplate.update("UPDATE products SET quantity = ? WHERE id = ?", 4, productId);
                return 1L;
            });

            // act
            List<Result> results = runAll(List.of(readThenWrite, readThenWrite));

            // assert - 둘 다 성공했는데 최종 재고가 4 라서 "성공 차감 수 + 최종 재고 = 초기 재고" 불변식이 깨진다
            assertNoTechnicalError(results);
            assertThat(count(results, Outcome.SUCCESS)).isEqualTo(2);
            int finalStock = stockOf(productId);
            assertThat(finalStock).isEqualTo(4);
            assertThat(2 + finalStock).isNotEqualTo(5);
        }
    }

    @DisplayName("[실제 서비스] 주문을 동시에 확정할 때, ")
    @Nested
    class ConfirmOrderConcurrently {
        @DisplayName("재고 5인 상품을 1개씩 사는 주문 8개를 동시에 확정하면, 5개만 재고를 확보해 PAYMENT_PENDING 이 되고 PG create 도 5회이며, 3개는 재고 부족으로 거절되고 최종 재고는 0이다.")
        @Test
        void reservesOnlyUpToStock_whenManyOrdersCompeteForOneProduct() throws Exception {
            // arrange
            int initialStock = 5;
            Long productId = saveProduct("경쟁 상품", 100L, initialStock);
            List<Long> orderIds = new ArrayList<>();
            List<Callable<Long>> tasks = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                Long userId = saveUser();
                Long orderId = createOrder(userId, productId, 1);
                orderIds.add(orderId);
                tasks.add(confirmTask(userId, orderId));
            }

            // act
            List<Result> results = runAll(tasks);

            // assert
            assertNoTechnicalError(results);
            assertThat(count(results, Outcome.SUCCESS)).isEqualTo(5);
            assertThat(count(results, Outcome.STOCK_REJECTED)).isEqualTo(3);
            assertThat(stockOf(productId)).isZero();
            assertThat(paymentPendingCount(orderIds)).isEqualTo(5);
            assertThat(fakePaymentGateway.createCount()).isEqualTo(5);

            // 공식 검증 - 각 주문 수량은 1
            assertThat(initialStock - count(results, Outcome.SUCCESS)).isEqualTo(stockOf(productId));
            assertThat(total(results)).isEqualTo(8);
        }
    }

    private Callable<Long> confirmTask(Long userId, Long orderId) {
        return () -> orderFacade.confirmOrder(userId, orderId,
            new OrderCommand.Confirm("k-" + orderId, "TEST", "0000-0000-0000-0000")).order().totalAmount();
    }

    /**
     * 모든 worker 를 기동해 시작 신호만 맞춘 뒤, 각 결과를 성공 / 업무 거절 / 기술 오류로 수집한다.
     * 반환 순서는 tasks 순서와 같다.
     */
    private List<Result> runAll(List<Callable<Long>> tasks) throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Long>> futures = new ArrayList<>();
            for (Callable<Long> task : tasks) {
                futures.add(executor.submit(() -> {
                    if (!start.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                        throw new TimeoutException("시작 신호를 기다리다 시간이 초과되었다.");
                    }
                    return task.call();
                }));
            }
            start.countDown();

            List<Result> results = new ArrayList<>();
            for (Future<Long> future : futures) {
                results.add(collect(future));
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    private Result collect(Future<Long> future) throws InterruptedException {
        try {
            return new Result(Outcome.SUCCESS, future.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS), null);
        } catch (ExecutionException e) {
            return classify(e.getCause());
        } catch (TimeoutException e) {
            return new Result(Outcome.TECHNICAL, 0L, e);
        }
    }

    private static Result classify(Throwable error) {
        if (error instanceof CoreException core && core.getErrorType() == ErrorType.CONFLICT
            && STOCK_REJECTED_MESSAGE.equals(core.getMessage())) {
            return new Result(Outcome.STOCK_REJECTED, 0L, null);
        }
        return new Result(Outcome.TECHNICAL, 0L, error);
    }

    private static void assertNoTechnicalError(List<Result> results) {
        List<Throwable> errors = results.stream()
            .filter(result -> result.outcome() == Outcome.TECHNICAL)
            .map(Result::error)
            .toList();
        assertThat(errors)
            .withFailMessage("기술 오류 %d건: %s", errors.size(), errors)
            .isEmpty();
    }

    private static long count(List<Result> results, Outcome outcome) {
        return results.stream().filter(result -> result.outcome() == outcome).count();
    }

    private static long total(List<Result> results) {
        return count(results, Outcome.SUCCESS)
            + count(results, Outcome.STOCK_REJECTED)
            + count(results, Outcome.TECHNICAL);
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("장벽 대기 실패", e);
        }
    }

    private Long saveUser() {
        return userJpaRepository.save(new User()).getId();
    }

    private Long saveProduct(String name, long price, int stock) {
        Product product = new Product(1L, name, new Price(price));
        product.changeStock(stock);
        return productJpaRepository.save(product).getId();
    }

    private Long createOrder(Long userId, Long productId, int quantity) {
        return orderFacade.createOrder(userId, List.of(new OrderCommand.Item(productId, quantity))).orderId();
    }

    private int stockOf(Long productId) {
        return productJpaRepository.findById(productId).orElseThrow().getStock().getQuantity();
    }

    private long paymentPendingCount(List<Long> orderIds) {
        return orderIds.stream()
            .map(id -> orderJpaRepository.findById(id).orElseThrow())
            .filter(order -> order.getStatus() == Order.OrderStatus.PAYMENT_PENDING)
            .count();
    }
}