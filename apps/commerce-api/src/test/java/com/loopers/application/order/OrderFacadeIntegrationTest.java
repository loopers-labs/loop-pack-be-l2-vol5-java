package com.loopers.application.order;

import com.loopers.domain.order.Order;
import com.loopers.domain.point.Point;
import com.loopers.domain.product.Price;
import com.loopers.domain.product.Product;
import com.loopers.domain.user.User;
import com.loopers.infrastructure.order.OrderJpaRepository;
import com.loopers.infrastructure.point.PointJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.infrastructure.user.UserJpaRepository;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 주문 확정의 원자성을 실제 MySQL 에서 검증한다.
 * 클래스 단위 @Transactional 을 쓰지 않아 Facade 트랜잭션이 실제로 commit/rollback 되게 한다.
 */
@SpringBootTest
class OrderFacadeIntegrationTest {

    private final OrderFacade orderFacade;
    private final UserJpaRepository userJpaRepository;
    private final ProductJpaRepository productJpaRepository;
    private final PointJpaRepository pointJpaRepository;
    private final OrderJpaRepository orderJpaRepository;
    private final JdbcTemplate jdbcTemplate;
    private final DatabaseCleanUp databaseCleanUp;

    @Autowired
    public OrderFacadeIntegrationTest(
        OrderFacade orderFacade,
        UserJpaRepository userJpaRepository,
        ProductJpaRepository productJpaRepository,
        PointJpaRepository pointJpaRepository,
        OrderJpaRepository orderJpaRepository,
        JdbcTemplate jdbcTemplate,
        DatabaseCleanUp databaseCleanUp
    ) {
        this.orderFacade = orderFacade;
        this.userJpaRepository = userJpaRepository;
        this.productJpaRepository = productJpaRepository;
        this.pointJpaRepository = pointJpaRepository;
        this.orderJpaRepository = orderJpaRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.databaseCleanUp = databaseCleanUp;
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("주문을 확정할 때, ")
    @Nested
    class ConfirmOrder {
        @DisplayName("여러 품목의 DRAFT 주문이면, 재고·포인트가 차감되고 CONFIRMED 와 주문 품목 단가 합이 결제액으로 저장된다.")
        @Test
        void deductsStockAndPointAndConfirms_whenDraftOrderHasMultipleItems() {
            // arrange
            Long userId = saveUser();
            Long firstProductId = saveProduct("첫 상품", 1000L, 10);
            Long secondProductId = saveProduct("둘째 상품", 500L, 3);
            savePoint(userId, 5000L);
            Long orderId = createOrder(userId, firstProductId, 2, secondProductId, 1);

            // 결제액은 생성 시점 단가의 합이어야 하므로, 확정 전에 상품 가격을 바꿔 둔다
            Product first = productJpaRepository.findById(firstProductId).orElseThrow();
            first.changePrice(new Price(9999L));
            productJpaRepository.save(first);

            // act
            OrderInfo result = orderFacade.confirmOrder(userId, orderId);

            // assert - 응답과 새로 조회한 DB 상태가 모두 확정 결과를 반영한다
            assertThat(result.status()).isEqualTo("CONFIRMED");
            assertThat(result.paidAmount()).isEqualTo(2500L);

            Order found = orderJpaRepository.findById(orderId).orElseThrow();
            assertThat(found.getStatus()).isEqualTo(Order.OrderStatus.CONFIRMED);
            assertThat(found.getPaidAmount()).isEqualTo(2500L);
            assertThat(stockOf(firstProductId)).isEqualTo(8);
            assertThat(stockOf(secondProductId)).isEqualTo(2);
            assertThat(balanceOf(userId)).isEqualTo(2500L);
        }
    }

    @DisplayName("주문을 확정하는 중 실패하면, ")
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
        @DisplayName("앞선 재고 차감 UPDATE 가 DB 에 나간 뒤 실패해도, 주문 상태·결제액·재고·잔액이 전부 변경 전으로 돌아간다.")
        @Test
        void restoresEverything_whenLaterStockUpdateFails() {
            // arrange
            Long userId = saveUser();
            Long firstProductId = saveProduct("첫 상품", 1000L, 10);
            Long failingProductId = saveProduct(FAILING_PRODUCT_NAME, 500L, 3);
            savePoint(userId, 5000L);
            Long orderId = createOrder(userId, firstProductId, 2, failingProductId, 1);
            jdbcTemplate.execute(
                "ALTER TABLE products ADD CONSTRAINT " + FAIL_CONSTRAINT
                    + " CHECK (name <> '" + FAILING_PRODUCT_NAME + "' OR quantity = 3)"
            );

            // act - 실패 원인이 주입한 CHECK 제약인지까지 확인한다
            assertThatThrownBy(() -> orderFacade.confirmOrder(userId, orderId))
                .rootCause().hasMessageContaining(FAIL_CONSTRAINT);

            // assert - 새 트랜잭션에서 재조회해 DB 상태를 확인한다
            Order found = orderJpaRepository.findById(orderId).orElseThrow();
            assertThat(found.getStatus()).isEqualTo(Order.OrderStatus.DRAFT);
            assertThat(found.getPaidAmount()).isZero();
            assertThat(stockOf(firstProductId)).isEqualTo(10);
            assertThat(stockOf(failingProductId)).isEqualTo(3);
            assertThat(balanceOf(userId)).isEqualTo(5000L);
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

    private void savePoint(Long userId, long balance) {
        Point point = new Point(userId);
        point.charge(balance);
        pointJpaRepository.save(point);
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

    private long balanceOf(Long userId) {
        return pointJpaRepository.findByUserId(userId).orElseThrow().getBalance();
    }
}
