package com.loopers.application.order;

import com.loopers.domain.order.Order;
import com.loopers.domain.product.Price;
import com.loopers.domain.product.Product;
import com.loopers.domain.user.User;
import com.loopers.infrastructure.order.OrderJpaRepository;
import com.loopers.infrastructure.payment.FakePaymentGateway;
import com.loopers.infrastructure.payment.FakePaymentGatewayConfig;
import com.loopers.infrastructure.payment.PaymentAttemptJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.infrastructure.product.StockReservationJpaRepository;
import com.loopers.infrastructure.user.UserJpaRepository;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 주문 확정의 TX A(재고 확보·시도 저장) 원자성을 실제 MySQL 에서 검증한다.
 * 정상 흐름은 OrderPaymentIntegrationTest 가 맡고, 여기서는 TX A 중간 실패가 전부 되돌아가는지만 본다.
 * 클래스 단위 @Transactional 을 쓰지 않아 TX A 가 실제로 commit/rollback 되게 한다.
 */
@SpringBootTest
@Import(FakePaymentGatewayConfig.class)
class OrderFacadeIntegrationTest {

    private final OrderFacade orderFacade;
    private final FakePaymentGateway fakePaymentGateway;
    private final UserJpaRepository userJpaRepository;
    private final ProductJpaRepository productJpaRepository;
    private final OrderJpaRepository orderJpaRepository;
    private final PaymentAttemptJpaRepository paymentAttemptJpaRepository;
    private final StockReservationJpaRepository stockReservationJpaRepository;
    private final JdbcTemplate jdbcTemplate;
    private final DatabaseCleanUp databaseCleanUp;

    @Autowired
    public OrderFacadeIntegrationTest(
        OrderFacade orderFacade,
        FakePaymentGateway fakePaymentGateway,
        UserJpaRepository userJpaRepository,
        ProductJpaRepository productJpaRepository,
        OrderJpaRepository orderJpaRepository,
        PaymentAttemptJpaRepository paymentAttemptJpaRepository,
        StockReservationJpaRepository stockReservationJpaRepository,
        JdbcTemplate jdbcTemplate,
        DatabaseCleanUp databaseCleanUp
    ) {
        this.orderFacade = orderFacade;
        this.fakePaymentGateway = fakePaymentGateway;
        this.userJpaRepository = userJpaRepository;
        this.productJpaRepository = productJpaRepository;
        this.orderJpaRepository = orderJpaRepository;
        this.paymentAttemptJpaRepository = paymentAttemptJpaRepository;
        this.stockReservationJpaRepository = stockReservationJpaRepository;
        this.jdbcTemplate = jdbcTemplate;
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

    @DisplayName("TX A 에서 재고를 확보하는 중 실패하면, ")
    @Nested
    class ConfirmOrderFailure {
        private static final String FAILING_PRODUCT_NAME = "확정 실패 상품";
        private static final String FAIL_CONSTRAINT = "chk_order_confirm_fail";

        @AfterEach
        void dropFailConstraint() {
            jdbcTemplate.execute("ALTER TABLE products DROP CHECK " + FAIL_CONSTRAINT);
        }

        /**
         * 실패 주입은 테스트 구성(DB CHECK 제약)으로만 한다.
         * ID 가 작은 첫 상품의 재고 UPDATE 는 DB 에 나가고, ID 가 큰 둘째 상품의 재고 UPDATE 만 거절된다.
         */
        @DisplayName("앞선 재고 차감 UPDATE 가 DB 에 나간 뒤 실패해도, 주문 상태·결제수단·결제액·재고가 전부 변경 전으로 돌아가고 시도·예약은 남지 않으며 PG 는 호출되지 않는다.")
        @Test
        void restoresEverything_whenLaterStockUpdateFails() {
            // arrange
            Long userId = saveUser();
            Long firstProductId = saveProduct("첫 상품", 1000L, 10);
            Long failingProductId = saveProduct(FAILING_PRODUCT_NAME, 500L, 3);
            Long orderId = createOrder(userId, firstProductId, 2, failingProductId, 1);
            jdbcTemplate.execute(
                "ALTER TABLE products ADD CONSTRAINT " + FAIL_CONSTRAINT
                    + " CHECK (name <> '" + FAILING_PRODUCT_NAME + "' OR quantity = 3)"
            );

            // act - 실패 원인이 주입한 CHECK 제약인지까지 확인한다
            assertThatThrownBy(() -> orderFacade.confirmOrder(userId, orderId,
                new OrderCommand.Confirm("k-" + orderId, "TEST", "0000-0000-0000-0000")))
                .rootCause().hasMessageContaining(FAIL_CONSTRAINT);

            // assert - 새 트랜잭션에서 재조회해 DB 상태를 확인한다
            Order found = orderJpaRepository.findById(orderId).orElseThrow();
            assertThat(found.getStatus()).isEqualTo(Order.OrderStatus.DRAFT);
            assertThat(found.getPaymentMethod()).isNull();
            assertThat(found.getPaidAmount()).isZero();
            assertThat(stockOf(firstProductId)).isEqualTo(10);
            assertThat(stockOf(failingProductId)).isEqualTo(3);
            assertThat(paymentAttemptJpaRepository.count()).isZero();
            assertThat(stockReservationJpaRepository.findAllByOrderId(orderId)).isEmpty();
            assertThat(fakePaymentGateway.createCount()).isZero();
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

    private Long createOrder(Long userId, Long firstProductId, int firstQuantity, Long secondProductId, int secondQuantity) {
        return orderFacade.createOrder(userId, List.of(
            new OrderCommand.Item(firstProductId, firstQuantity),
            new OrderCommand.Item(secondProductId, secondQuantity)
        )).orderId();
    }

    private int stockOf(Long productId) {
        return productJpaRepository.findById(productId).orElseThrow().getStock().getQuantity();
    }
}