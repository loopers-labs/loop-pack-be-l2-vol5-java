package com.loopers.application.payment;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderRepository;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.order.PaymentMethod;
import com.loopers.domain.payment.PaymentAttempt;
import com.loopers.domain.payment.PaymentAttemptRepository;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

// 결제 요청 TX A가 주문 전이·재고 차감·시도 생성을 함께 commit하거나 함께 되돌리는지 확인한다.
@SpringBootTest
class PaymentRequestTransactionIntegrationTest {

    private static final long INITIAL_FIRST_STOCK = 5L;
    private static final long INITIAL_SECOND_STOCK = 4L;
    private static final long INITIAL_POINT_BALANCE = 1_000L;
    private static final long TOTAL_AMOUNT = 500L;

    @Autowired
    private PaymentRequestFacade paymentRequestFacade;

    @Autowired
    private BrandRepository brandRepository;

    @MockitoSpyBean
    private ProductRepository productRepository;

    @MockitoSpyBean
    private PaymentAttemptRepository paymentAttemptRepository;

    @MockitoSpyBean
    private PaymentGateway paymentGateway;

    @Autowired
    private PointRepository pointRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("여러 품목의 결제 요청은 주문 전이·모든 품목의 재고 차감·시도 생성을 함께 commit하고 포인트는 쓰지 않는다.")
    @Test
    void commitsOrderStockAndAttemptTogether() {
        Fixture fixture = createFixture();

        paymentRequestFacade.requestPayment(fixture.userId(), fixture.orderId(), UUID.randomUUID().toString(), "TEST", "0000");

        Order order = orderRepository.findById(fixture.orderId()).orElseThrow();
        PaymentAttempt attempt = paymentAttemptRepository.findByOrderId(fixture.orderId()).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(order.getPaymentMethod()).isEqualTo(PaymentMethod.PG);
        assertThat(order.getTotalAmount()).isEqualTo(TOTAL_AMOUNT);
        assertThat(order.getPaymentAmount()).isNull();
        assertThat(order.getItems()).hasSize(2);
        assertThat(attempt.getAmount()).isEqualTo(TOTAL_AMOUNT);
        assertThat(stock(fixture.firstProductId())).isEqualTo(3L);
        assertThat(stock(fixture.secondProductId())).isEqualTo(3L);
        assertThat(point(fixture)).isEqualTo(INITIAL_POINT_BALANCE);
    }

    @DisplayName("주문 전이와 재고 차감 뒤 시도 저장이 실패하면 TX A 전체를 되돌리고 PG를 호출하지 않는다.")
    @Test
    void rollsBackEveryChangeWhenAttemptSaveFails() {
        Fixture fixture = createFixture();
        AtomicReference<DatabaseState> stateBeforeFailure = new AtomicReference<>();
        doAnswer(invocation -> {
            stateBeforeFailure.set(readDatabaseState(fixture));
            throw new IllegalStateException("시도 저장 전 실패 주입");
        }).when(paymentAttemptRepository).save(any());

        assertThatThrownBy(() -> paymentRequestFacade.requestPayment(
            fixture.userId(), fixture.orderId(), UUID.randomUUID().toString(), "TEST", "0000"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("시도 저장 전 실패 주입");

        assertThat(stateBeforeFailure.get()).isEqualTo(new DatabaseState(
            3L, 3L, INITIAL_POINT_BALANCE, "PAYMENT_PENDING", "PG", null
        ));
        assertThat(readDatabaseState(fixture)).isEqualTo(new DatabaseState(
            INITIAL_FIRST_STOCK, INITIAL_SECOND_STOCK, INITIAL_POINT_BALANCE, "DRAFT", null, null
        ));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM payment_attempts", Long.class)).isZero();
        verify(paymentGateway, never()).create(any());
    }

    @DisplayName("첫 품목 차감 후 다음 품목의 재고가 부족하면 409로 TX A 전체를 되돌린다.")
    @Test
    void rollsBackFirstItemDeductionWhenSecondItemHasInsufficientStock() {
        Fixture fixture = createFixture(0L);
        AtomicReference<DatabaseState> stateBeforeRejection = new AtomicReference<>();
        doAnswer(invocation -> {
            int updatedRows = (int) invocation.callRealMethod();
            assertThat(updatedRows).isZero();
            // 두 번째 상품의 조건부 차감이 거절된 시점에 앞선 변경이 같은 트랜잭션 안에 있음을 확인한다.
            stateBeforeRejection.set(readDatabaseState(fixture));
            return updatedRows;
        }).when(productRepository).decreaseActiveStock(eq(fixture.secondProductId()), eq(1), any(ZonedDateTime.class));

        assertThatThrownBy(() -> paymentRequestFacade.requestPayment(
            fixture.userId(), fixture.orderId(), UUID.randomUUID().toString(), "TEST", "0000"))
            .isInstanceOfSatisfying(CoreException.class, exception -> {
                assertThat(exception.getErrorType()).isEqualTo(ErrorType.CONFLICT);
                assertThat(exception.getCustomMessage()).isEqualTo("상품이 삭제되었거나 재고가 부족합니다.");
            });

        assertThat(stateBeforeRejection.get()).isEqualTo(new DatabaseState(
            3L, 0L, INITIAL_POINT_BALANCE, "PAYMENT_PENDING", "PG", null
        ));
        verify(paymentAttemptRepository, never()).save(any());
        verify(paymentGateway, never()).create(any());
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            Order order = orderRepository.findById(fixture.orderId()).orElseThrow();
            assertThat(order.getStatus()).isEqualTo(OrderStatus.DRAFT);
            assertThat(order.getPaymentMethod()).isNull();
            assertThat(order.getItems()).extracting(
                OrderItem::getProductId, OrderItem::getProductName, OrderItem::getUnitPrice, OrderItem::getQuantity
            ).containsExactlyInAnyOrder(
                tuple(fixture.firstProductId(), "Air Max", 100L, 2),
                tuple(fixture.secondProductId(), "Pegasus", 300L, 1)
            );
        });
        assertThat(stock(fixture.firstProductId())).isEqualTo(INITIAL_FIRST_STOCK);
        assertThat(stock(fixture.secondProductId())).isZero();
        assertThat(point(fixture)).isEqualTo(INITIAL_POINT_BALANCE);
    }

    private Fixture createFixture() {
        return createFixture(INITIAL_SECOND_STOCK);
    }

    private Fixture createFixture(long secondStock) {
        User user = userRepository.save(User.create());
        Brand brand = brandRepository.save(Brand.create("Nike"));

        Product firstProduct = Product.create(brand.getId(), "Air Max", 100L);
        firstProduct.changeStockTo(INITIAL_FIRST_STOCK);
        firstProduct = productRepository.save(firstProduct);

        Product secondProduct = Product.create(brand.getId(), "Pegasus", 300L);
        secondProduct.changeStockTo(secondStock);
        secondProduct = productRepository.save(secondProduct);

        pointRepository.save(Point.create(user.getId(), new PointBalance(INITIAL_POINT_BALANCE)));

        Order order = orderRepository.save(Order.create(user.getId(), List.of(
            OrderItem.create(firstProduct.getId(), firstProduct.getName(), firstProduct.getPrice(), 2),
            OrderItem.create(secondProduct.getId(), secondProduct.getName(), secondProduct.getPrice(), 1)
        )));

        return new Fixture(user.getId(), firstProduct.getId(), secondProduct.getId(), order.getId());
    }

    private long stock(Long productId) {
        return productRepository.findById(productId).orElseThrow().getStock().amount();
    }

    private long point(Fixture fixture) {
        return pointRepository.findByUserId(fixture.userId()).orElseThrow().getBalance().amount();
    }

    // 호출한 스레드의 트랜잭션에 참여하므로 TX A 안에서는 commit 전 변경을 본다.
    private DatabaseState readDatabaseState(Fixture fixture) {
        long firstStock = jdbcTemplate.queryForObject(
            "SELECT stock FROM products WHERE id = ?", Long.class, fixture.firstProductId());
        long secondStock = jdbcTemplate.queryForObject(
            "SELECT stock FROM products WHERE id = ?", Long.class, fixture.secondProductId());
        long pointBalance = jdbcTemplate.queryForObject(
            "SELECT balance FROM points WHERE user_id = ?", Long.class, fixture.userId());
        Map<String, Object> order = jdbcTemplate.queryForMap(
            "SELECT status, payment_method, payment_amount FROM orders WHERE id = ?", fixture.orderId());
        Number paymentAmount = (Number) order.get("payment_amount");
        return new DatabaseState(firstStock, secondStock, pointBalance, (String) order.get("status"),
            (String) order.get("payment_method"), paymentAmount == null ? null : paymentAmount.longValue());
    }

    private record Fixture(Long userId, Long firstProductId, Long secondProductId, Long orderId) {}

    private record DatabaseState(long firstStock, long secondStock, long pointBalance, String orderStatus,
                                 String paymentMethod, Long paymentAmount) {}
}
