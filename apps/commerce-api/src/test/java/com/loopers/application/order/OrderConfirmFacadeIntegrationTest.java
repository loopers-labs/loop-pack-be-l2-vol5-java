package com.loopers.application.order;

import com.loopers.domain.order.OrderItemCommand;
import com.loopers.domain.order.OrderModel;
import com.loopers.domain.order.OrderRepository;
import com.loopers.application.order.OrderFacade;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.point.PointChangeCause;
import com.loopers.application.point.PointFacade;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.product.ProductRepository;
import com.loopers.application.product.ProductFacade;
import com.loopers.domain.product.StockChangeCause;
import com.loopers.domain.user.UserModel;
import com.loopers.fixture.LockProbe;
import com.loopers.fixture.OrderStateReader;
import com.loopers.fixture.OrderStateReader.ConfirmState;
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
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

@DisplayName("OrderConfirmFacade 는 주문 확정으로 Order·Point·Stock 을 한 트랜잭션에서 변경한다.")
@SpringBootTest(properties = "logging.level.org.hibernate.orm.jdbc.bind=TRACE")
class OrderConfirmFacadeIntegrationTest {

    @Autowired
    private OrderConfirmFacade orderConfirmFacade;
    @Autowired
    private OrderFacade orderFacade;
    @Autowired
    private PointFacade pointFacade;
    @Autowired
    private ProductFacade productFacade;
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
    private OrderStateReader orderStateReader;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private LockProbe lockProbe;
    @PersistenceContext
    private EntityManager entityManager;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    /** LockProbe 확인 스레드 종료를 확인하지 못했으면 살아 있는 트랜잭션이 남았을 수 있어 TRUNCATE 를 보류하고 실패시킨다. */
    @AfterEach
    void tearDown() {
        if (!lockProbe.allThreadsTerminated()) {
            throw new IllegalStateException("LockProbe 확인 스레드 종료 미확인: TRUNCATE 를 보류했다");
        }
        databaseCleanUp.truncateAllTables();
    }

    private long stockOf(ProductModel product) {
        return productJpaRepository.findById(product.getId()).orElseThrow().getStockQuantity();
    }

    private long balanceOf(UserModel user) {
        return pointJpaRepository.findByUserId(user.getId()).orElseThrow().getBalance();
    }

    /** 실패 요청 전후 비교용: 주문·품목·잔액·재고·전체 History 를 새 조회로 읽는다. */
    private ConfirmState stateOf(Long orderId, UserModel user, ProductModel... products) {
        return orderStateReader.confirmState(orderId, user.getId(),
            Arrays.stream(products).map(ProductModel::getId).toList());
    }

    @DisplayName("확정 성공")
    @Nested
    class Success {
        @DisplayName("총액 7,000 을 잔액 10,000 에서 차감해 3,000 이 남고 품목별 재고를 차감하며 CONFIRMED 가 된다.")
        @Test
        void confirmsOrder() {
            UserModel user = userFixture.createUserWithPoint();
            pointFacade.charge(user.getId(), 10_000L);
            ProductModel shirt = productFixture.createProduct("티셔츠", 2_000L, 5L);
            ProductModel pants = productFixture.createProduct("바지", 3_000L, 4L);
            OrderModel order = orderFacade.create(user.getId(), List.of(
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
                () -> assertThat(orderStateReader.orderRow(order.getId())).isEqualTo(List.of(
                    user.getId(), OrderStatus.CONFIRMED, 7_000L, 7_000L, 7_000L, List.of(
                        List.of(shirt.getId(), 2L, 2_000L), List.of(pants.getId(), 1L, 3_000L)))),
                () -> assertThat(balanceOf(user)).isEqualTo(3_000L),
                () -> assertThat(stockOf(shirt)).isEqualTo(3L),
                () -> assertThat(stockOf(pants)).isEqualTo(3L)
            );
        }

        @DisplayName("포인트 사용 이력 1건과 품목별 재고 차감 이력을 주문 식별자와 함께 남긴다.")
        @Test
        void savesHistories() {
            UserModel user = userFixture.createUserWithPoint();
            pointFacade.charge(user.getId(), 10_000L);
            ProductModel shirt = productFixture.createProduct("티셔츠", 2_000L, 5L);
            ProductModel pants = productFixture.createProduct("바지", 3_000L, 4L);
            OrderModel order = orderFacade.create(user.getId(), List.of(
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
                () -> assertThat(pointHistories.get(0).getChangedAmount()).isEqualTo(7_000L),
                () -> assertThat(stockHistories).hasSize(2),
                () -> assertThat(stockHistories).allMatch(history -> order.getId().equals(history.getOrderId())),
                () -> assertThat(stockHistories)
                    .extracting(history -> List.of(history.getProductId(), history.getBeforeQuantity(),
                        history.getAfterQuantity(), history.getChangedQuantity()))
                    .containsExactlyInAnyOrder(
                        List.of(shirt.getId(), 5L, 3L, 2L),
                        List.of(pants.getId(), 4L, 3L, 1L))
            );
        }

        @DisplayName("저장된 품목 순서와 관계없이 상품 ID 오름차순으로 재고를 차감하고 이력을 남긴다.")
        @Test
        void deductsInProductIdOrder() {
            UserModel user = userFixture.createUserWithPoint();
            pointFacade.charge(user.getId(), 10_000L);
            ProductModel shirt = productFixture.createProduct("티셔츠", 2_000L, 5L);
            ProductModel pants = productFixture.createProduct("바지", 3_000L, 4L);
            ProductModel socks = productFixture.createProduct("양말", 1_000L, 3L);
            OrderModel order = orderFacade.create(user.getId(), List.of(
                new OrderItemCommand(socks.getId(), 1L),
                new OrderItemCommand(shirt.getId(), 1L),
                new OrderItemCommand(pants.getId(), 1L)
            ));

            orderConfirmFacade.confirm(user.getId(), order.getId());

            assertThat(orderStateReader.stockHistoryRows())
                .as("History id 순서 = 처리 순서")
                .extracting(row -> row.get(1))
                .containsExactly(shirt.getId(), pants.getId(), socks.getId());
        }

        @DisplayName("잔액과 주문 총액이 같으면 잔액 0 으로 확정할 수 있다.")
        @Test
        void allowsExactBalance() {
            UserModel user = userFixture.createUserWithPoint();
            pointFacade.charge(user.getId(), 4_000L);
            ProductModel shirt = productFixture.createProduct("티셔츠", 2_000L, 5L);
            OrderModel order = orderFacade.create(user.getId(),
                List.of(new OrderItemCommand(shirt.getId(), 2L)));

            orderConfirmFacade.confirm(user.getId(), order.getId());

            assertThat(balanceOf(user)).isZero();
        }

        @DisplayName("주문 생성 뒤 상품 가격이 올라도 확정은 주문 당시 단가로 계산한 4,000 만 차감한다.")
        @Test
        void chargesSnapshotPriceAfterPriceChange() {
            UserModel user = userFixture.createUserWithPoint();
            pointFacade.charge(user.getId(), 10_000L);
            ProductModel shirt = productFixture.createProduct("티셔츠", 2_000L, 5L);
            OrderModel order = orderFacade.create(user.getId(),
                List.of(new OrderItemCommand(shirt.getId(), 2L)));

            productFacade.update(shirt.getId(), "티셔츠", 5_000L);

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
            pointFacade.charge(user.getId(), 6_999L);
            ProductModel shirt = productFixture.createProduct("티셔츠", 2_000L, 5L);
            ProductModel pants = productFixture.createProduct("바지", 3_000L, 4L);
            OrderModel order = orderFacade.create(user.getId(), List.of(
                new OrderItemCommand(shirt.getId(), 2L),
                new OrderItemCommand(pants.getId(), 1L)
            ));

            ConfirmState before = stateOf(order.getId(), user, shirt, pants);

            assertThatThrownBy(() -> orderConfirmFacade.confirm(user.getId(), order.getId()))
                .isInstanceOf(CoreException.class)
                .extracting("errorType")
                .isEqualTo(ErrorType.INSUFFICIENT_POINT);

            assertAll(
                () -> assertThat(stateOf(order.getId(), user, shirt, pants)).isEqualTo(before),
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
            pointFacade.charge(user.getId(), 10_000L);
            ProductModel shirt = productFixture.createProduct("티셔츠", 2_000L, 5L);
            ProductModel pants = productFixture.createProduct("바지", 3_000L, 1L);
            OrderModel order = orderFacade.create(user.getId(), List.of(
                new OrderItemCommand(shirt.getId(), 2L),
                new OrderItemCommand(pants.getId(), 2L)
            ));

            ConfirmState before = stateOf(order.getId(), user, shirt, pants);

            assertThatThrownBy(() -> orderConfirmFacade.confirm(user.getId(), order.getId()))
                .isInstanceOf(CoreException.class)
                .extracting("errorType")
                .isEqualTo(ErrorType.INSUFFICIENT_STOCK);

            assertAll(
                () -> assertThat(stateOf(order.getId(), user, shirt, pants)).isEqualTo(before),
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
            pointFacade.charge(user.getId(), 10_000L);
            ProductModel shirt = productFixture.createProduct("티셔츠", 2_000L, 5L);
            OrderModel order = orderFacade.create(user.getId(),
                List.of(new OrderItemCommand(shirt.getId(), 2L)));

            productFacade.changeStock(shirt.getId(), 1L);

            ConfirmState before = stateOf(order.getId(), user, shirt);

            assertThatThrownBy(() -> orderConfirmFacade.confirm(user.getId(), order.getId()))
                .isInstanceOf(CoreException.class)
                .extracting("errorType")
                .isEqualTo(ErrorType.INSUFFICIENT_STOCK);

            assertAll(
                () -> assertThat(stateOf(order.getId(), user, shirt)).isEqualTo(before),
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
            pointFacade.charge(user.getId(), 10_000L);
            ProductModel shirt = productFixture.createProduct("티셔츠", 2_000L, 5L);
            OrderModel order = orderFacade.create(user.getId(),
                List.of(new OrderItemCommand(shirt.getId(), 2L)));
            orderConfirmFacade.confirm(user.getId(), order.getId());

            ConfirmState before = stateOf(order.getId(), user, shirt);

            assertThatThrownBy(() -> orderConfirmFacade.confirm(user.getId(), order.getId()))
                .isInstanceOf(CoreException.class)
                .extracting("errorType")
                .isEqualTo(ErrorType.ORDER_NOT_CONFIRMABLE);

            assertAll(
                () -> assertThat(stateOf(order.getId(), user, shirt)).isEqualTo(before),
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
            pointFacade.charge(other.getId(), 10_000L);
            ProductModel shirt = productFixture.createProduct("티셔츠", 2_000L, 5L);
            OrderModel order = orderFacade.create(owner.getId(),
                List.of(new OrderItemCommand(shirt.getId(), 2L)));

            ConfirmState before = stateOf(order.getId(), other, shirt);

            assertThatThrownBy(() -> orderConfirmFacade.confirm(other.getId(), order.getId()))
                .isInstanceOf(CoreException.class)
                .extracting("errorType")
                .isEqualTo(ErrorType.ORDER_NOT_FOUND);
            assertAll(
                () -> assertThat(stateOf(order.getId(), other, shirt)).isEqualTo(before),
                () -> assertThat(balanceOf(other)).isEqualTo(10_000L),
                () -> assertThat(stockOf(shirt)).isEqualTo(5L)
            );
        }

        @DisplayName("없는 주문의 확정은 ORDER_NOT_FOUND 로 거절한다.")
        @Test
        void rejectsUnknownOrder() {
            UserModel user = userFixture.createUserWithPoint();
            pointFacade.charge(user.getId(), 10_000L);
            ProductModel shirt = productFixture.createProduct("티셔츠", 2_000L, 5L);
            ConfirmState before = stateOf(999_999L, user, shirt);

            assertThatThrownBy(() -> orderConfirmFacade.confirm(user.getId(), 999_999L))
                .isInstanceOf(CoreException.class)
                .extracting("errorType")
                .isEqualTo(ErrorType.ORDER_NOT_FOUND);
            assertThat(stateOf(999_999L, user, shirt)).isEqualTo(before);
        }

        @DisplayName("주문 생성 뒤 상품이 삭제되면 확정을 PRODUCT_NOT_FOUND 로 거절하고 상태를 유지한다.")
        @Test
        void rejectsDeletedProduct() {
            UserModel user = userFixture.createUserWithPoint();
            pointFacade.charge(user.getId(), 10_000L);
            ProductModel shirt = productFixture.createProduct("티셔츠", 2_000L, 5L);
            OrderModel order = orderFacade.create(user.getId(),
                List.of(new OrderItemCommand(shirt.getId(), 2L)));

            ProductModel stored = productJpaRepository.findById(shirt.getId()).orElseThrow();
            stored.delete();
            productJpaRepository.save(stored);

            ConfirmState before = stateOf(order.getId(), user, shirt);

            assertThatThrownBy(() -> orderConfirmFacade.confirm(user.getId(), order.getId()))
                .isInstanceOf(CoreException.class)
                .extracting("errorType")
                .isEqualTo(ErrorType.PRODUCT_NOT_FOUND);
            assertAll(
                () -> assertThat(stateOf(order.getId(), user, shirt)).isEqualTo(before),
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
            OrderModel order = orderFacade.create(user.getId(),
                List.of(new OrderItemCommand(shirt.getId(), 2L)));

            ConfirmState before = stateOf(order.getId(), user, shirt);

            assertThatThrownBy(() -> orderConfirmFacade.confirm(user.getId(), order.getId()))
                .isInstanceOf(CoreException.class)
                .extracting("errorType")
                .isEqualTo(ErrorType.POINT_NOT_INITIALIZED);
            assertThat(stockOf(shirt)).isEqualTo(5L);
            assertThat(stateOf(order.getId(), user, shirt)).isEqualTo(before);
        }
    }
    @DisplayName("Order 잠금 범위")
    @Nested
    class OrderLock {
        private static final String LOCK_ORDER = "SELECT id FROM orders WHERE id = ? FOR UPDATE NOWAIT";
        private static final String LOCK_ITEMS = "SELECT id FROM order_item WHERE order_id = ? FOR UPDATE NOWAIT";

        @DisplayName("잠금 조회는 Order 행만 잠그고 품목을 복원하지 않으며, 같은 트랜잭션에서 품목을 따로 복원한다.")
        @Test
        void locksOnlyOrderRowAndLoadsItemsSeparately() {
            UserModel user = userFixture.createUserWithPoint();
            ProductModel shirt = productFixture.createProduct("티셔츠", 2_000L, 5L);
            ProductModel pants = productFixture.createProduct("바지", 3_000L, 4L);
            OrderModel order = orderFacade.create(user.getId(), List.of(
                new OrderItemCommand(shirt.getId(), 2L),
                new OrderItemCommand(pants.getId(), 1L)
            ));

            List<Object> observed = transactionTemplate.execute(status -> {
                OrderModel locked = orderRepository.findForUpdate(order.getId()).orElseThrow();
                boolean itemsLoadedByLock = entityManager.getEntityManagerFactory().getPersistenceUnitUtil()
                    .isLoaded(locked, "items");
                LockProbe.Result orderRow = lockProbe.probe(LOCK_ORDER, order.getId());
                LockProbe.Result itemRows = lockProbe.probe(LOCK_ITEMS, order.getId());
                List<Long> itemProductIds = locked.getItems().stream()
                    .map(item -> item.getProductId()).sorted().toList();
                return List.of(itemsLoadedByLock, orderRow, itemRows, itemProductIds);
            });

            assertAll(
                () -> assertThat(observed.get(0)).as("잠금 조회 직후 품목 복원 여부").isEqualTo(false),
                () -> assertThat(observed.get(1)).as("다른 connection 의 같은 Order 잠금 시도")
                    .isEqualTo(LockProbe.Result.LOCKED),
                () -> assertThat(observed.get(2)).as("다른 connection 의 품목 잠금 시도")
                    .isEqualTo(LockProbe.Result.ACQUIRED),
                () -> assertThat(observed.get(3)).isEqualTo(List.of(shirt.getId(), pants.getId()))
            );
        }

        @DisplayName("트랜잭션이 끝나면 Order 잠금이 풀린다.")
        @Test
        void releasesOrderLockAfterTransaction() {
            UserModel user = userFixture.createUserWithPoint();
            ProductModel shirt = productFixture.createProduct("티셔츠", 2_000L, 5L);
            OrderModel order = orderFacade.create(user.getId(), List.of(new OrderItemCommand(shirt.getId(), 1L)));

            transactionTemplate.executeWithoutResult(status -> orderRepository.findForUpdate(order.getId()));

            assertThat(lockProbe.probe(LOCK_ORDER, order.getId())).isEqualTo(LockProbe.Result.ACQUIRED);
        }
    }

    @DisplayName("Product 잠금 범위")
    @Nested
    class ProductLock {
        private static final String LOCK_PRODUCT = "SELECT id FROM product WHERE id = ? FOR UPDATE NOWAIT";

        @DisplayName("단건 변경용 조회는 활성 Product 행을 트랜잭션이 끝날 때까지 잠그고, 일반 조회는 잠그지 않는다.")
        @Test
        void locksSingleActiveProduct() {
            ProductModel shirt = productFixture.createProduct("티셔츠", 2_000L, 5L);

            LockProbe.Result whileLocked = transactionTemplate.execute(status -> {
                productRepository.findActiveForUpdate(shirt.getId()).orElseThrow();
                return lockProbe.probe(LOCK_PRODUCT, shirt.getId());
            });
            LockProbe.Result whileRead = transactionTemplate.execute(status -> {
                productRepository.findActive(shirt.getId()).orElseThrow();
                return lockProbe.probe(LOCK_PRODUCT, shirt.getId());
            });

            assertAll(
                () -> assertThat(whileLocked).isEqualTo(LockProbe.Result.LOCKED),
                () -> assertThat(whileRead).isEqualTo(LockProbe.Result.ACQUIRED),
                () -> assertThat(lockProbe.probe(LOCK_PRODUCT, shirt.getId())).as("트랜잭션 종료 후")
                    .isEqualTo(LockProbe.Result.ACQUIRED)
            );
        }

        @DisplayName("단건 변경용 조회는 삭제되었거나 없는 상품을 돌려주지 않는다.")
        @Test
        void excludesDeletedOrUnknownProduct() {
            ProductModel deleted = productFixture.createDeletedProduct("단종 티셔츠", 2_000L, 5L);

            List<Boolean> found = transactionTemplate.execute(status -> List.of(
                productRepository.findActiveForUpdate(deleted.getId()).isPresent(),
                productRepository.findActiveForUpdate(999_999L).isPresent()));

            assertThat(found).containsExactly(false, false);
        }

        @DisplayName("여러 Product 변경용 조회는 요청한 활성 상품을 ID 오름차순으로 돌려주고 모두 잠근다. 삭제·없는 ID 는 빠진다.")
        @Test
        void locksActiveProductsInIdOrder() {
            ProductModel shirt = productFixture.createProduct("티셔츠", 2_000L, 5L);
            ProductModel pants = productFixture.createProduct("바지", 3_000L, 4L);
            ProductModel deleted = productFixture.createDeletedProduct("단종 양말", 1_000L, 3L);

            List<Object> observed = transactionTemplate.execute(status -> {
                List<Long> ids = productRepository.findAllActiveByIdsForUpdate(
                        List.of(shirt.getId(), pants.getId(), deleted.getId(), 999_999L)).stream()
                    .map(ProductModel::getId).toList();
                return List.of(ids, lockProbe.probe(LOCK_PRODUCT, shirt.getId()),
                    lockProbe.probe(LOCK_PRODUCT, pants.getId()));
            });

            assertAll(
                () -> assertThat(observed.get(0)).isEqualTo(List.of(shirt.getId(), pants.getId())),
                () -> assertThat(observed.get(1)).isEqualTo(LockProbe.Result.LOCKED),
                () -> assertThat(observed.get(2)).isEqualTo(LockProbe.Result.LOCKED)
            );
        }

        @DisplayName("여러 Product 일반 조회는 잠그지 않는다.")
        @Test
        void keepsBulkReadUnlocked() {
            ProductModel shirt = productFixture.createProduct("티셔츠", 2_000L, 5L);
            ProductModel pants = productFixture.createProduct("바지", 3_000L, 4L);

            List<LockProbe.Result> observed = transactionTemplate.execute(status -> {
                productRepository.findAllActiveByIds(List.of(shirt.getId(), pants.getId()));
                return List.of(lockProbe.probe(LOCK_PRODUCT, shirt.getId()),
                    lockProbe.probe(LOCK_PRODUCT, pants.getId()));
            });

            assertThat(observed).containsOnly(LockProbe.Result.ACQUIRED);
        }
    }
}
