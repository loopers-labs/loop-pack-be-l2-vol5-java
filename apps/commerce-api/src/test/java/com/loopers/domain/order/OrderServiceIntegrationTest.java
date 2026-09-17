package com.loopers.domain.order;

import com.loopers.domain.common.Money;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.user.UserModel;
import com.loopers.fixture.ProductFixture;
import com.loopers.fixture.UserFixture;
import com.loopers.infrastructure.order.OrderJpaRepository;
import com.loopers.infrastructure.point.PointJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

@DisplayName("OrderService 는 주문 생성 시 상품을 읽어 검증하고 DRAFT 로 저장한다.")
@SpringBootTest
class OrderServiceIntegrationTest {

    @Autowired
    private OrderService orderService;
    @Autowired
    private UserFixture userFixture;
    @Autowired
    private ProductFixture productFixture;
    @Autowired
    private OrderJpaRepository orderJpaRepository;
    @Autowired
    private ProductJpaRepository productJpaRepository;
    @Autowired
    private PointJpaRepository pointJpaRepository;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("주문 생성")
    @Nested
    class Create {
        @DisplayName("여러 품목을 주문 당시 단가로 저장하고 총액을 계산한다.")
        @Test
        void savesDraftOrderWithItems() {
            UserModel user = userFixture.createUserWithPoint();
            ProductModel shirt = productFixture.createProduct("티셔츠", 2_000L, 10L);
            ProductModel pants = productFixture.createProduct("바지", 3_000L, 10L);

            OrderModel created = orderService.create(user.getId(), List.of(
                new OrderItemCommand(shirt.getId(), 2L),
                new OrderItemCommand(pants.getId(), 1L)
            ));

            OrderModel saved = orderJpaRepository.findById(created.getId()).orElseThrow();
            assertAll(
                () -> assertThat(saved.getStatus()).isEqualTo(OrderStatus.DRAFT),
                () -> assertThat(saved.getOrderTotal()).isEqualTo(Money.of(7_000L)),
                () -> assertThat(saved.getUserId()).isEqualTo(user.getId())
            );
        }

        @DisplayName("주문 생성 시 재고와 포인트를 차감하지 않는다.")
        @Test
        void doesNotDeductStockOrPoint() {
            UserModel user = userFixture.createUserWithPoint();
            ProductModel shirt = productFixture.createProduct("티셔츠", 2_000L, 10L);

            orderService.create(user.getId(), List.of(new OrderItemCommand(shirt.getId(), 2L)));

            assertAll(
                () -> assertThat(productJpaRepository.findById(shirt.getId()).orElseThrow().getStockQuantity())
                    .isEqualTo(10L),
                () -> assertThat(pointJpaRepository.findByUserId(user.getId()).orElseThrow().getBalance()).isZero()
            );
        }

        @DisplayName("같은 상품의 수량은 합산해 하나의 품목으로 저장한다.")
        @Test
        void mergesDuplicateItems() {
            UserModel user = userFixture.createUserWithPoint();
            ProductModel shirt = productFixture.createProduct("티셔츠", 1_000L, 10L);

            OrderModel created = orderService.create(user.getId(), List.of(
                new OrderItemCommand(shirt.getId(), 2L),
                new OrderItemCommand(shirt.getId(), 3L)
            ));

            List<OrderItemModel> items = orderService.getOrder(user.getId(), created.getId()).getItems();
            assertAll(
                () -> assertThat(items).hasSize(1),
                () -> assertThat(items.get(0).getQuantity()).isEqualTo(5L),
                () -> assertThat(created.getOrderTotal()).isEqualTo(Money.of(5_000L))
            );
        }

        @DisplayName("품목이 비어 있으면 INVALID_ORDER_ITEMS 로 거절하고 주문을 저장하지 않는다.")
        @Test
        void rejectsEmptyItems() {
            UserModel user = userFixture.createUserWithPoint();

            assertThatThrownBy(() -> orderService.create(user.getId(), List.of()))
                .isInstanceOf(CoreException.class)
                .extracting("errorType")
                .isEqualTo(ErrorType.INVALID_ORDER_ITEMS);
            assertThat(orderJpaRepository.findAll()).isEmpty();
        }

        @DisplayName("수량이 1 미만인 품목이 있으면 상품 조회 전에 INVALID_ORDER_QUANTITY 로 거절한다.")
        @Test
        void rejectsNonPositiveQuantity() {
            UserModel user = userFixture.createUserWithPoint();
            ProductModel shirt = productFixture.createProduct("티셔츠", 1_000L, 10L);

            assertThatThrownBy(() -> orderService.create(user.getId(), List.of(
                new OrderItemCommand(shirt.getId(), 1L),
                new OrderItemCommand(shirt.getId(), 0L)
            )))
                .isInstanceOf(CoreException.class)
                .extracting("errorType")
                .isEqualTo(ErrorType.INVALID_ORDER_QUANTITY);
            assertThat(orderJpaRepository.findAll()).isEmpty();
        }

        @DisplayName("존재하지 않는 상품은 PRODUCT_NOT_FOUND 로 거절하고 주문을 저장하지 않는다.")
        @Test
        void rejectsUnknownProduct() {
            UserModel user = userFixture.createUserWithPoint();

            assertThatThrownBy(() -> orderService.create(user.getId(),
                List.of(new OrderItemCommand(999_999L, 1L))))
                .isInstanceOf(CoreException.class)
                .extracting("errorType")
                .isEqualTo(ErrorType.PRODUCT_NOT_FOUND);
            assertThat(orderJpaRepository.findAll()).isEmpty();
        }

        @DisplayName("삭제된 상품은 새 주문에서 제외하고 PRODUCT_NOT_FOUND 로 거절한다.")
        @Test
        void rejectsDeletedProduct() {
            UserModel user = userFixture.createUserWithPoint();
            ProductModel deleted = productFixture.createDeletedProduct("단종 티셔츠", 1_000L, 10L);

            assertThatThrownBy(() -> orderService.create(user.getId(),
                List.of(new OrderItemCommand(deleted.getId(), 1L))))
                .isInstanceOf(CoreException.class)
                .extracting("errorType")
                .isEqualTo(ErrorType.PRODUCT_NOT_FOUND);
            assertThat(orderJpaRepository.findAll()).isEmpty();
        }

        @DisplayName("합산 수량이 표현 범위를 넘으면 NUMERIC_OVERFLOW 로 거절한다.")
        @Test
        void rejectsOverflowingQuantity() {
            UserModel user = userFixture.createUserWithPoint();
            ProductModel shirt = productFixture.createProduct("티셔츠", 1_000L, 10L);

            assertThatThrownBy(() -> orderService.create(user.getId(), List.of(
                new OrderItemCommand(shirt.getId(), Long.MAX_VALUE),
                new OrderItemCommand(shirt.getId(), 1L)
            )))
                .isInstanceOf(CoreException.class)
                .extracting("errorType")
                .isEqualTo(ErrorType.NUMERIC_OVERFLOW);
            assertThat(orderJpaRepository.findAll()).isEmpty();
        }

        @DisplayName("주문 생성 뒤 상품 가격이 바뀌어도 저장된 주문 당시 단가는 변하지 않는다.")
        @Test
        void keepsUnitPriceSnapshot() {
            UserModel user = userFixture.createUserWithPoint();
            ProductModel shirt = productFixture.createProduct("티셔츠", 2_000L, 10L);
            OrderModel created = orderService.create(user.getId(),
                List.of(new OrderItemCommand(shirt.getId(), 2L)));

            ProductModel stored = productJpaRepository.findById(shirt.getId()).orElseThrow();
            stored.update("티셔츠", 5_000L);
            productJpaRepository.save(stored);

            OrderModel reloaded = orderService.getOrder(user.getId(), created.getId());
            assertAll(
                () -> assertThat(reloaded.getItems().get(0).getUnitPrice()).isEqualTo(Money.of(2_000L)),
                () -> assertThat(reloaded.getOrderTotal()).isEqualTo(Money.of(4_000L))
            );
        }
    }

    @DisplayName("주문 상세 조회")
    @Nested
    class GetOrder {
        @DisplayName("자신의 주문을 품목과 함께 조회한다.")
        @Test
        void returnsOwnOrder() {
            UserModel user = userFixture.createUserWithPoint();
            ProductModel shirt = productFixture.createProduct("티셔츠", 2_000L, 10L);
            OrderModel created = orderService.create(user.getId(),
                List.of(new OrderItemCommand(shirt.getId(), 2L)));

            OrderModel found = orderService.getOrder(user.getId(), created.getId());

            assertAll(
                () -> assertThat(found.getId()).isEqualTo(created.getId()),
                () -> assertThat(found.getItems()).hasSize(1),
                () -> assertThat(found.getItems().get(0).getProductId()).isEqualTo(shirt.getId())
            );
        }

        @DisplayName("다른 사용자의 주문은 ORDER_NOT_FOUND 로 거절한다.")
        @Test
        void rejectsOtherUsersOrder() {
            UserModel owner = userFixture.createUserWithPoint();
            UserModel other = userFixture.createUserWithPoint();
            ProductModel shirt = productFixture.createProduct("티셔츠", 2_000L, 10L);
            OrderModel created = orderService.create(owner.getId(),
                List.of(new OrderItemCommand(shirt.getId(), 1L)));

            assertThatThrownBy(() -> orderService.getOrder(other.getId(), created.getId()))
                .isInstanceOf(CoreException.class)
                .extracting("errorType")
                .isEqualTo(ErrorType.ORDER_NOT_FOUND);
        }

        @DisplayName("없는 주문은 ORDER_NOT_FOUND 로 거절한다.")
        @Test
        void rejectsUnknownOrder() {
            UserModel user = userFixture.createUserWithPoint();

            assertThatThrownBy(() -> orderService.getOrder(user.getId(), 999_999L))
                .isInstanceOf(CoreException.class)
                .extracting("errorType")
                .isEqualTo(ErrorType.ORDER_NOT_FOUND);
        }
    }
}
