package com.loopers.application.order;

import com.loopers.domain.order.OrderItemCommand;
import com.loopers.domain.order.OrderModel;
import com.loopers.domain.order.OrderService;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.point.PointChangeCause;
import com.loopers.domain.point.PointService;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.product.ProductService;
import com.loopers.domain.product.StockChangeCause;
import com.loopers.domain.user.UserModel;
import com.loopers.fixture.ProductFixture;
import com.loopers.fixture.UserFixture;
import com.loopers.infrastructure.order.OrderJpaRepository;
import com.loopers.infrastructure.point.PointHistoryJpaRepository;
import com.loopers.infrastructure.point.PointJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.infrastructure.product.StockHistoryJpaRepository;
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

@DisplayName("OrderConfirmFacade 는 주문 확정으로 Order·Point·Stock 을 한 트랜잭션에서 변경한다.")
@SpringBootTest
class OrderConfirmFacadeIntegrationTest {

    @Autowired
    private OrderConfirmFacade orderConfirmFacade;
    @Autowired
    private OrderService orderService;
    @Autowired
    private PointService pointService;
    @Autowired
    private ProductService productService;
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
    private PointHistoryJpaRepository pointHistoryJpaRepository;
    @Autowired
    private StockHistoryJpaRepository stockHistoryJpaRepository;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private long stockOf(ProductModel product) {
        return productJpaRepository.findById(product.getId()).orElseThrow().getStockQuantity();
    }

    private long balanceOf(UserModel user) {
        return pointJpaRepository.findByUserId(user.getId()).orElseThrow().getBalance();
    }

    @DisplayName("확정 성공")
    @Nested
    class Success {
        @DisplayName("총액 7,000 을 잔액 10,000 에서 차감해 3,000 이 남고 품목별 재고를 차감하며 CONFIRMED 가 된다.")
        @Test
        void confirmsOrder() {
            UserModel user = userFixture.createUserWithPoint();
            pointService.charge(user.getId(), 10_000L);
            ProductModel shirt = productFixture.createProduct("티셔츠", 2_000L, 5L);
            ProductModel pants = productFixture.createProduct("바지", 3_000L, 4L);
            OrderModel order = orderService.create(user.getId(), List.of(
                new OrderItemCommand(shirt.getId(), 2L),
                new OrderItemCommand(pants.getId(), 1L)
            ));

            OrderInfo confirmed = orderConfirmFacade.confirm(user.getId(), order.getId());

            OrderModel saved = orderJpaRepository.findById(order.getId()).orElseThrow();
            assertAll(
                () -> assertThat(confirmed.status()).isEqualTo(OrderStatus.CONFIRMED.name()),
                () -> assertThat(confirmed.orderTotal()).isEqualTo(7_000L),
                () -> assertThat(confirmed.usedPointAmount()).isEqualTo(7_000L),
                () -> assertThat(confirmed.paymentAmount()).isEqualTo(7_000L),
                () -> assertThat(confirmed.items()).hasSize(2),
                () -> assertThat(saved.getStatus()).isEqualTo(OrderStatus.CONFIRMED),
                () -> assertThat(balanceOf(user)).isEqualTo(3_000L),
                () -> assertThat(stockOf(shirt)).isEqualTo(3L),
                () -> assertThat(stockOf(pants)).isEqualTo(3L)
            );
        }

        @DisplayName("포인트 사용 이력 1건과 품목별 재고 차감 이력을 주문 식별자와 함께 남긴다.")
        @Test
        void savesHistories() {
            UserModel user = userFixture.createUserWithPoint();
            pointService.charge(user.getId(), 10_000L);
            ProductModel shirt = productFixture.createProduct("티셔츠", 2_000L, 5L);
            ProductModel pants = productFixture.createProduct("바지", 3_000L, 4L);
            OrderModel order = orderService.create(user.getId(), List.of(
                new OrderItemCommand(shirt.getId(), 2L),
                new OrderItemCommand(pants.getId(), 1L)
            ));

            orderConfirmFacade.confirm(user.getId(), order.getId());

            var pointHistories = pointHistoryJpaRepository.findAll().stream()
                .filter(history -> history.getCause() == PointChangeCause.ORDER_USE)
                .toList();
            var stockHistories = stockHistoryJpaRepository.findAll().stream()
                .filter(history -> history.getCause() == StockChangeCause.ORDER_DEDUCTION)
                .toList();
            assertAll(
                () -> assertThat(pointHistories).hasSize(1),
                () -> assertThat(pointHistories.get(0).getOrderId()).isEqualTo(order.getId()),
                () -> assertThat(pointHistories.get(0).getBeforeBalance()).isEqualTo(10_000L),
                () -> assertThat(pointHistories.get(0).getAfterBalance()).isEqualTo(3_000L),
                () -> assertThat(stockHistories).hasSize(2),
                () -> assertThat(stockHistories).allMatch(history -> order.getId().equals(history.getOrderId()))
            );
        }

        @DisplayName("잔액과 주문 총액이 같으면 잔액 0 으로 확정할 수 있다.")
        @Test
        void allowsExactBalance() {
            UserModel user = userFixture.createUserWithPoint();
            pointService.charge(user.getId(), 4_000L);
            ProductModel shirt = productFixture.createProduct("티셔츠", 2_000L, 5L);
            OrderModel order = orderService.create(user.getId(),
                List.of(new OrderItemCommand(shirt.getId(), 2L)));

            orderConfirmFacade.confirm(user.getId(), order.getId());

            assertThat(balanceOf(user)).isZero();
        }

        @DisplayName("주문 생성 뒤 상품 가격이 올라도 확정은 주문 당시 단가로 계산한 4,000 만 차감한다.")
        @Test
        void chargesSnapshotPriceAfterPriceChange() {
            UserModel user = userFixture.createUserWithPoint();
            pointService.charge(user.getId(), 10_000L);
            ProductModel shirt = productFixture.createProduct("티셔츠", 2_000L, 5L);
            OrderModel order = orderService.create(user.getId(),
                List.of(new OrderItemCommand(shirt.getId(), 2L)));

            productService.update(shirt.getId(), "티셔츠", 5_000L);

            OrderInfo confirmed = orderConfirmFacade.confirm(user.getId(), order.getId());

            OrderModel saved = orderJpaRepository.findById(order.getId()).orElseThrow();
            assertAll(
                () -> assertThat(confirmed.status()).isEqualTo(OrderStatus.CONFIRMED.name()),
                () -> assertThat(confirmed.orderTotal()).isEqualTo(4_000L),
                () -> assertThat(confirmed.usedPointAmount()).isEqualTo(4_000L),
                () -> assertThat(confirmed.paymentAmount()).isEqualTo(4_000L),
                () -> assertThat(saved.getStatus()).isEqualTo(OrderStatus.CONFIRMED),
                () -> assertThat(balanceOf(user)).isEqualTo(6_000L)
            );
        }
    }

    @DisplayName("확정 거절")
    @Nested
    class Rejection {
        @DisplayName("포인트가 부족하면 주문·포인트·재고·이력을 모두 유지한다.")
        @Test
        void rejectsInsufficientPoint() {
            UserModel user = userFixture.createUserWithPoint();
            pointService.charge(user.getId(), 6_999L);
            ProductModel shirt = productFixture.createProduct("티셔츠", 2_000L, 5L);
            ProductModel pants = productFixture.createProduct("바지", 3_000L, 4L);
            OrderModel order = orderService.create(user.getId(), List.of(
                new OrderItemCommand(shirt.getId(), 2L),
                new OrderItemCommand(pants.getId(), 1L)
            ));

            assertThatThrownBy(() -> orderConfirmFacade.confirm(user.getId(), order.getId()))
                .isInstanceOf(CoreException.class)
                .extracting("errorType")
                .isEqualTo(ErrorType.INSUFFICIENT_POINT);

            assertAll(
                () -> assertThat(orderJpaRepository.findById(order.getId()).orElseThrow().getStatus())
                    .isEqualTo(OrderStatus.DRAFT),
                () -> assertThat(balanceOf(user)).isEqualTo(6_999L),
                () -> assertThat(stockOf(shirt)).isEqualTo(5L),
                () -> assertThat(stockOf(pants)).isEqualTo(4L),
                () -> assertThat(stockHistoryJpaRepository.findAll()).noneMatch(
                    history -> history.getCause() == StockChangeCause.ORDER_DEDUCTION),
                () -> assertThat(pointHistoryJpaRepository.findAll()).noneMatch(
                    history -> history.getCause() == PointChangeCause.ORDER_USE)
            );
        }

        @DisplayName("한 품목의 재고가 부족하면 이미 차감한 다른 품목의 재고와 포인트까지 되돌린다.")
        @Test
        void rejectsInsufficientStockAndRollsBack() {
            UserModel user = userFixture.createUserWithPoint();
            pointService.charge(user.getId(), 10_000L);
            ProductModel shirt = productFixture.createProduct("티셔츠", 2_000L, 5L);
            ProductModel pants = productFixture.createProduct("바지", 3_000L, 1L);
            OrderModel order = orderService.create(user.getId(), List.of(
                new OrderItemCommand(shirt.getId(), 2L),
                new OrderItemCommand(pants.getId(), 2L)
            ));

            assertThatThrownBy(() -> orderConfirmFacade.confirm(user.getId(), order.getId()))
                .isInstanceOf(CoreException.class)
                .extracting("errorType")
                .isEqualTo(ErrorType.INSUFFICIENT_STOCK);

            assertAll(
                () -> assertThat(orderJpaRepository.findById(order.getId()).orElseThrow().getStatus())
                    .isEqualTo(OrderStatus.DRAFT),
                () -> assertThat(balanceOf(user)).isEqualTo(10_000L),
                () -> assertThat(stockOf(shirt)).isEqualTo(5L),
                () -> assertThat(stockOf(pants)).isEqualTo(1L),
                () -> assertThat(stockHistoryJpaRepository.findAll()).noneMatch(
                    history -> history.getCause() == StockChangeCause.ORDER_DEDUCTION)
            );
        }

        @DisplayName("주문 생성 뒤 관리자가 재고를 줄이면 생성 당시가 아닌 확정 시점의 재고로 판단해 INSUFFICIENT_STOCK 으로 거절한다.")
        @Test
        void rejectsWhenStockDroppedAfterDraft() {
            UserModel user = userFixture.createUserWithPoint();
            pointService.charge(user.getId(), 10_000L);
            ProductModel shirt = productFixture.createProduct("티셔츠", 2_000L, 5L);
            OrderModel order = orderService.create(user.getId(),
                List.of(new OrderItemCommand(shirt.getId(), 2L)));

            productService.changeStock(shirt.getId(), 1L);

            assertThatThrownBy(() -> orderConfirmFacade.confirm(user.getId(), order.getId()))
                .isInstanceOf(CoreException.class)
                .extracting("errorType")
                .isEqualTo(ErrorType.INSUFFICIENT_STOCK);

            assertAll(
                () -> assertThat(orderJpaRepository.findById(order.getId()).orElseThrow().getStatus())
                    .isEqualTo(OrderStatus.DRAFT),
                () -> assertThat(balanceOf(user)).isEqualTo(10_000L),
                () -> assertThat(stockOf(shirt)).isEqualTo(1L),
                () -> assertThat(stockHistoryJpaRepository.findAll()).noneMatch(
                    history -> history.getCause() == StockChangeCause.ORDER_DEDUCTION),
                () -> assertThat(pointHistoryJpaRepository.findAll()).noneMatch(
                    history -> history.getCause() == PointChangeCause.ORDER_USE),
                () -> assertThat(stockHistoryJpaRepository.findAll().stream()
                    .filter(history -> history.getCause() == StockChangeCause.ADMIN_CHANGE).toList()).hasSize(1)
            );
        }

        @DisplayName("이미 확정한 주문을 다시 확정하면 ORDER_NOT_CONFIRMABLE 로 거절하고 모든 상태를 유지한다.")
        @Test
        void rejectsAlreadyConfirmedOrder() {
            UserModel user = userFixture.createUserWithPoint();
            pointService.charge(user.getId(), 10_000L);
            ProductModel shirt = productFixture.createProduct("티셔츠", 2_000L, 5L);
            OrderModel order = orderService.create(user.getId(),
                List.of(new OrderItemCommand(shirt.getId(), 2L)));
            orderConfirmFacade.confirm(user.getId(), order.getId());

            assertThatThrownBy(() -> orderConfirmFacade.confirm(user.getId(), order.getId()))
                .isInstanceOf(CoreException.class)
                .extracting("errorType")
                .isEqualTo(ErrorType.ORDER_NOT_CONFIRMABLE);

            assertAll(
                () -> assertThat(balanceOf(user)).isEqualTo(6_000L),
                () -> assertThat(stockOf(shirt)).isEqualTo(3L),
                () -> assertThat(stockHistoryJpaRepository.findAll().stream()
                    .filter(history -> history.getCause() == StockChangeCause.ORDER_DEDUCTION).toList()).hasSize(1)
            );
        }

        @DisplayName("다른 사용자의 주문 확정은 ORDER_NOT_FOUND 로 거절한다.")
        @Test
        void rejectsOtherUsersOrder() {
            UserModel owner = userFixture.createUserWithPoint();
            UserModel other = userFixture.createUserWithPoint();
            pointService.charge(other.getId(), 10_000L);
            ProductModel shirt = productFixture.createProduct("티셔츠", 2_000L, 5L);
            OrderModel order = orderService.create(owner.getId(),
                List.of(new OrderItemCommand(shirt.getId(), 2L)));

            assertThatThrownBy(() -> orderConfirmFacade.confirm(other.getId(), order.getId()))
                .isInstanceOf(CoreException.class)
                .extracting("errorType")
                .isEqualTo(ErrorType.ORDER_NOT_FOUND);
            assertAll(
                () -> assertThat(balanceOf(other)).isEqualTo(10_000L),
                () -> assertThat(stockOf(shirt)).isEqualTo(5L)
            );
        }

        @DisplayName("없는 주문의 확정은 ORDER_NOT_FOUND 로 거절한다.")
        @Test
        void rejectsUnknownOrder() {
            UserModel user = userFixture.createUserWithPoint();

            assertThatThrownBy(() -> orderConfirmFacade.confirm(user.getId(), 999_999L))
                .isInstanceOf(CoreException.class)
                .extracting("errorType")
                .isEqualTo(ErrorType.ORDER_NOT_FOUND);
        }

        @DisplayName("주문 생성 뒤 상품이 삭제되면 확정을 PRODUCT_NOT_FOUND 로 거절하고 상태를 유지한다.")
        @Test
        void rejectsDeletedProduct() {
            UserModel user = userFixture.createUserWithPoint();
            pointService.charge(user.getId(), 10_000L);
            ProductModel shirt = productFixture.createProduct("티셔츠", 2_000L, 5L);
            OrderModel order = orderService.create(user.getId(),
                List.of(new OrderItemCommand(shirt.getId(), 2L)));

            ProductModel stored = productJpaRepository.findById(shirt.getId()).orElseThrow();
            stored.delete();
            productJpaRepository.save(stored);

            assertThatThrownBy(() -> orderConfirmFacade.confirm(user.getId(), order.getId()))
                .isInstanceOf(CoreException.class)
                .extracting("errorType")
                .isEqualTo(ErrorType.PRODUCT_NOT_FOUND);
            assertAll(
                () -> assertThat(orderJpaRepository.findById(order.getId()).orElseThrow().getStatus())
                    .isEqualTo(OrderStatus.DRAFT),
                () -> assertThat(balanceOf(user)).isEqualTo(10_000L)
            );
        }

        @DisplayName("Point 가 없는 사용자의 확정은 POINT_NOT_INITIALIZED 로 거절한다.")
        @Test
        void rejectsWhenPointIsNotInitialized() {
            UserModel user = userFixture.createUserWithoutPoint();
            ProductModel shirt = productFixture.createProduct("티셔츠", 2_000L, 5L);
            OrderModel order = orderService.create(user.getId(),
                List.of(new OrderItemCommand(shirt.getId(), 2L)));

            assertThatThrownBy(() -> orderConfirmFacade.confirm(user.getId(), order.getId()))
                .isInstanceOf(CoreException.class)
                .extracting("errorType")
                .isEqualTo(ErrorType.POINT_NOT_INITIALIZED);
            assertThat(stockOf(shirt)).isEqualTo(5L);
        }
    }
}
