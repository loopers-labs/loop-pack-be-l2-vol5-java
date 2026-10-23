package com.loopers.application.order;

import com.loopers.fixture.PgTestSupport;
import com.loopers.fixture.PgCommands;
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

@DisplayName("OrderConfirmFacade 는 TX A·PG HTTP·TX B로 결제하고 포인트를 사용하지 않는다.")
@SpringBootTest
class OrderConfirmFacadeIntegrationTest extends PgTestSupport {

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
        @DisplayName("Point 없는 고객도 저장 총액으로 결제하고 품목별 재고를 확보한다.")
        @Test
        void confirmsOrder() {
            UserModel user = userFixture.createUserWithoutPoint();
            ProductModel shirt = productFixture.createProduct("티셔츠", 2000L, 5L);
            ProductModel pants = productFixture.createProduct("바지", 3000L, 4L);
            OrderInfo order = orderFacade.create(user.getId(), List.of(
                new OrderItemCommand(shirt.getId(), 2L), new OrderItemCommand(pants.getId(), 1L)));
            success(order.id());
            OrderInfo confirmed = orderConfirmFacade.confirm(user.getId(), order.id(), PgCommands.forOrder(order.id())).order();
            assertAll(
                () -> assertThat(confirmed.status()).isEqualTo("CONFIRMED"),
                () -> assertThat(confirmed.totalAmount()).isEqualTo(7000L),
                () -> assertThat(confirmed.paidAmount()).isEqualTo(7000L),
                () -> assertThat(confirmed.pointAmount()).isZero(),
                () -> assertThat(pointJpaRepository.findByUserId(user.getId())).isEmpty(),
                () -> assertThat(stockOf(shirt)).isEqualTo(3L),
                () -> assertThat(stockOf(pants)).isEqualTo(3L)
            );
        }

        @DisplayName("재고 확보 이력을 남기고 포인트 이력·잔액은 유지한다.")
        @Test
        void savesReservationWithoutPointUse() {
            UserModel user = userFixture.createUserWithPoint();
            pointFacade.charge(user.getId(), 10000L);
            ProductModel product = productFixture.createProduct("상품", 2000L, 5L);
            OrderInfo order = orderFacade.create(user.getId(), List.of(new OrderItemCommand(product.getId(), 2L)));
            var before = orderStateReader.pointHistoryRows();
            success(order.id());
            orderConfirmFacade.confirm(user.getId(), order.id(), PgCommands.forOrder(order.id()));
            assertThat(orderStateReader.pointHistoryRows()).isEqualTo(before);
            assertThat(balanceOf(user)).isEqualTo(10000L);
            assertThat(stockHistoryJpaRepository.findAll()).singleElement().satisfies(history -> {
                assertThat(history.getCause()).isEqualTo(StockChangeCause.ORDER_RESERVATION);
                assertThat(history.getOrderId()).isEqualTo(order.id());
            });
        }

        @DisplayName("저장된 품목 순서와 관계없이 상품 ID 오름차순으로 재고를 차감하고 이력을 남긴다.")
        @Test
        void deductsInProductIdOrder() {
            UserModel user = userFixture.createUserWithoutPoint();
            ProductModel first = productFixture.createProduct("먼저", 2000L, 5L);
            ProductModel second = productFixture.createProduct("나중", 3000L, 4L);
            OrderInfo order = orderFacade.create(user.getId(), List.of(
                new OrderItemCommand(second.getId(), 1L), new OrderItemCommand(first.getId(), 1L)));
            orderConfirmFacade.confirm(user.getId(), order.id(), PgCommands.forOrder(order.id()));
            assertThat(stockHistoryJpaRepository.findAll()).extracting(history -> history.getProductId())
                .containsExactly(first.getId(), second.getId());
        }

        @DisplayName("잔액이 0이어도 PG 결제는 성공한다.")
        @Test
        void allowsZeroPointBalance() {
            UserModel user = userFixture.createUserWithPoint();
            ProductModel product = productFixture.createProduct("상품", 2000L, 5L);
            OrderInfo order = orderFacade.create(user.getId(), List.of(new OrderItemCommand(product.getId(), 1L)));
            success(order.id());
            assertThat(orderConfirmFacade.confirm(user.getId(), order.id(), PgCommands.forOrder(order.id())).order().status())
                .isEqualTo("CONFIRMED");
            assertThat(balanceOf(user)).isZero();
        }

        @DisplayName("가격 변경 후에도 주문 당시 단가로 PG에 요청하고 포인트를 유지한다.")
        @Test
        void chargesSnapshotPriceAfterPriceChange() {
            UserModel user = userFixture.createUserWithPoint();
            pointFacade.charge(user.getId(), 10_000L);
            ProductModel shirt = productFixture.createProduct("티셔츠", 2_000L, 5L);
            OrderInfo order = orderFacade.create(user.getId(),
                List.of(new OrderItemCommand(shirt.getId(), 2L)));

            productFacade.update(shirt.getId(), "티셔츠", 5_000L);

            success(order.id());
            OrderInfo confirmed = orderConfirmFacade.confirm(user.getId(), order.id(), PgCommands.forOrder(order.id())).order();

            OrderModel saved = orderJpaRepository.findById(order.id()).orElseThrow();
            assertAll(
                () -> assertThat(confirmed.status()).isEqualTo(OrderStatus.CONFIRMED.name()),
                () -> assertThat(confirmed.totalAmount()).isEqualTo(4_000L),
                () -> assertThat(confirmed.pointAmount()).isZero(),
                () -> assertThat(confirmed.paidAmount()).isEqualTo(4_000L),
                () -> assertThat(saved.getStatus()).isEqualTo(OrderStatus.CONFIRMED),
                () -> assertThat(balanceOf(user)).isEqualTo(10_000L)
            );
        }
    }

    @DisplayName("확정 거절")
    @Nested
    class Rejection {
        @DisplayName("Point 잔액이 부족해도 PG 결제는 성공하고 잔액을 유지한다.")
        @Test
        void allowsInsufficientPointBalance() {
            UserModel user = userFixture.createUserWithPoint();
            pointFacade.charge(user.getId(), 1000L);
            ProductModel product = productFixture.createProduct("상품", 2000L, 5L);
            OrderInfo order = orderFacade.create(user.getId(), List.of(new OrderItemCommand(product.getId(), 2L)));
            success(order.id());
            assertThat(orderConfirmFacade.confirm(user.getId(), order.id(), PgCommands.forOrder(order.id())).order().status())
                .isEqualTo("CONFIRMED");
            assertThat(balanceOf(user)).isEqualTo(1000L);
        }

        @DisplayName("한 품목의 재고가 부족하면 이미 차감한 다른 품목의 재고와 포인트까지 되돌린다.")
        @Test
        void rejectsInsufficientStockAndRollsBack() {
            UserModel user = userFixture.createUserWithPoint();
            pointFacade.charge(user.getId(), 10_000L);
            ProductModel shirt = productFixture.createProduct("티셔츠", 2_000L, 5L);
            ProductModel pants = productFixture.createProduct("바지", 3_000L, 1L);
            OrderInfo order = orderFacade.create(user.getId(), List.of(
                new OrderItemCommand(shirt.getId(), 2L),
                new OrderItemCommand(pants.getId(), 2L)
            ));

            ConfirmState before = stateOf(order.id(), user, shirt, pants);

            assertThatThrownBy(() -> orderConfirmFacade.confirm(user.getId(), order.id(), PgCommands.forOrder(order.id())))
                .isInstanceOf(CoreException.class)
                .extracting("errorType")
                .isEqualTo(ErrorType.INSUFFICIENT_STOCK);

            assertThat(pg.state().counts().createReceived()).isZero();
            assertThat(pg.state().transactions()).isEmpty();
            assertAll(
                () -> assertThat(stateOf(order.id(), user, shirt, pants)).isEqualTo(before),
                () -> assertThat(orderJpaRepository.findById(order.id()).orElseThrow().getStatus())
                    .isEqualTo(OrderStatus.DRAFT),
                () -> assertThat(balanceOf(user)).isEqualTo(10_000L),
                () -> assertThat(stockOf(shirt)).isEqualTo(5L),
                () -> assertThat(stockOf(pants)).isEqualTo(1L),
                () -> assertThat(stockHistoryJpaRepository.findAll()).noneMatch(
                    history -> history.getCause() == StockChangeCause.ORDER_RESERVATION
                        || history.getCause() == StockChangeCause.ORDER_RELEASE)
            );
        }

        @DisplayName("주문 생성 뒤 관리자가 재고를 줄이면 생성 당시가 아닌 확정 시점의 재고로 판단해 INSUFFICIENT_STOCK 으로 거절한다.")
        @Test
        void rejectsWhenStockDroppedAfterDraft() {
            UserModel user = userFixture.createUserWithPoint();
            pointFacade.charge(user.getId(), 10_000L);
            ProductModel shirt = productFixture.createProduct("티셔츠", 2_000L, 5L);
            OrderInfo order = orderFacade.create(user.getId(),
                List.of(new OrderItemCommand(shirt.getId(), 2L)));

            productFacade.changeStock(shirt.getId(), 1L);

            ConfirmState before = stateOf(order.id(), user, shirt);

            assertThatThrownBy(() -> orderConfirmFacade.confirm(user.getId(), order.id(), PgCommands.forOrder(order.id())))
                .isInstanceOf(CoreException.class)
                .extracting("errorType")
                .isEqualTo(ErrorType.INSUFFICIENT_STOCK);

            assertThat(pg.state().counts().createReceived()).isZero();
            assertThat(pg.state().transactions()).isEmpty();
            assertAll(
                () -> assertThat(stateOf(order.id(), user, shirt)).isEqualTo(before),
                () -> assertThat(orderJpaRepository.findById(order.id()).orElseThrow().getStatus())
                    .isEqualTo(OrderStatus.DRAFT),
                () -> assertThat(balanceOf(user)).isEqualTo(10_000L),
                () -> assertThat(stockOf(shirt)).isEqualTo(1L),
                () -> assertThat(stockHistoryJpaRepository.findAll()).noneMatch(
                    history -> history.getCause() == StockChangeCause.ORDER_RESERVATION
                        || history.getCause() == StockChangeCause.ORDER_RELEASE),
                () -> assertThat(pointHistoryJpaRepository.findAll()).noneMatch(
                    history -> history.getCause() == PointChangeCause.ORDER_USE),
                () -> assertThat(stockHistoryJpaRepository.findAll().stream()
                    .filter(history -> history.getCause() == StockChangeCause.ADMIN_CHANGE).toList()).hasSize(1)
            );
        }

        @DisplayName("같은 PG 시도 재요청은 저장 결과를 반환하고 추가 효과가 없다.")
        @Test
        void returnsSamePgAttemptWithoutMoreEffects() {
            UserModel user = userFixture.createUserWithoutPoint();
            ProductModel product = productFixture.createProduct("상품", 2000L, 5L);
            OrderInfo order = orderFacade.create(user.getId(), List.of(new OrderItemCommand(product.getId(), 1L)));
            success(order.id());
            var first = orderConfirmFacade.confirm(user.getId(), order.id(), PgCommands.forOrder(order.id()));
            var second = orderConfirmFacade.confirm(user.getId(), order.id(), PgCommands.forOrder(order.id()));
            assertThat(second).isEqualTo(first);
            assertThat(pg.state().counts().createReceived()).isEqualTo(1);
            assertThat(stockOf(product)).isEqualTo(4L);
        }

        @DisplayName("다른 사용자의 주문 확정은 ORDER_NOT_FOUND 로 거절한다.")
        @Test
        void rejectsOtherUsersOrder() {
            UserModel owner = userFixture.createUserWithPoint();
            UserModel other = userFixture.createUserWithPoint();
            pointFacade.charge(other.getId(), 10_000L);
            ProductModel shirt = productFixture.createProduct("티셔츠", 2_000L, 5L);
            OrderInfo order = orderFacade.create(owner.getId(),
                List.of(new OrderItemCommand(shirt.getId(), 2L)));

            ConfirmState before = stateOf(order.id(), other, shirt);

            assertThatThrownBy(() -> orderConfirmFacade.confirm(other.getId(), order.id(), PgCommands.forOrder(order.id())))
                .isInstanceOf(CoreException.class)
                .extracting("errorType")
                .isEqualTo(ErrorType.ORDER_NOT_FOUND);
            assertAll(
                () -> assertThat(stateOf(order.id(), other, shirt)).isEqualTo(before),
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

            assertThatThrownBy(() -> orderConfirmFacade.confirm(user.getId(), 999_999L, PgCommands.forOrder(999_999L)))
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
            OrderInfo order = orderFacade.create(user.getId(),
                List.of(new OrderItemCommand(shirt.getId(), 2L)));

            ProductModel stored = productJpaRepository.findById(shirt.getId()).orElseThrow();
            stored.delete();
            productJpaRepository.save(stored);

            ConfirmState before = stateOf(order.id(), user, shirt);

            assertThatThrownBy(() -> orderConfirmFacade.confirm(user.getId(), order.id(), PgCommands.forOrder(order.id())))
                .isInstanceOf(CoreException.class)
                .extracting("errorType")
                .isEqualTo(ErrorType.PRODUCT_NOT_FOUND);
            assertAll(
                () -> assertThat(stateOf(order.id(), user, shirt)).isEqualTo(before),
                () -> assertThat(orderJpaRepository.findById(order.id()).orElseThrow().getStatus())
                    .isEqualTo(OrderStatus.DRAFT),
                () -> assertThat(balanceOf(user)).isEqualTo(10_000L)
            );
        }

        @DisplayName("Point 없이도 PG 결제가 가능하다.")
        @Test
        void allowsUninitializedPoint() {
            UserModel user = userFixture.createUserWithoutPoint();
            ProductModel product = productFixture.createProduct("상품", 2000L, 5L);
            OrderInfo order = orderFacade.create(user.getId(), List.of(new OrderItemCommand(product.getId(), 1L)));
            success(order.id());
            assertThat(orderConfirmFacade.confirm(user.getId(), order.id(), PgCommands.forOrder(order.id())).order().status())
                .isEqualTo("CONFIRMED");
            assertThat(pointJpaRepository.findByUserId(user.getId())).isEmpty();
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
            OrderInfo order = orderFacade.create(user.getId(), List.of(
                new OrderItemCommand(shirt.getId(), 2L),
                new OrderItemCommand(pants.getId(), 1L)
            ));

            List<Object> observed = transactionTemplate.execute(status -> {
                OrderModel locked = orderRepository.findForUpdate(order.id()).orElseThrow();
                boolean itemsLoadedByLock = entityManager.getEntityManagerFactory().getPersistenceUnitUtil()
                    .isLoaded(locked, "items");
                LockProbe.Result orderRow = lockProbe.probe(LOCK_ORDER, order.id());
                LockProbe.Result itemRows = lockProbe.probe(LOCK_ITEMS, order.id());
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
            OrderInfo order = orderFacade.create(user.getId(), List.of(new OrderItemCommand(shirt.getId(), 1L)));

            transactionTemplate.executeWithoutResult(status -> orderRepository.findForUpdate(order.id()));

            assertThat(lockProbe.probe(LOCK_ORDER, order.id())).isEqualTo(LockProbe.Result.ACQUIRED);
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
