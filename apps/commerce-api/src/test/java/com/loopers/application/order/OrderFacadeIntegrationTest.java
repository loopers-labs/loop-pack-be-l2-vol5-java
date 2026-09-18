package com.loopers.application.order;

import com.loopers.support.error.DomainError;
import com.loopers.support.error.DomainException;
import com.loopers.application.product.ProductFacade;
import com.loopers.application.brand.BrandFacade;
import com.loopers.application.point.PointFacade;
import com.loopers.domain.common.Money;
import com.loopers.domain.common.Quantity;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderQuantity;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.point.ChargeAmount;
import com.loopers.domain.point.PointService;
import com.loopers.domain.point.PointTransaction;
import com.loopers.domain.point.TransactionType;
import com.loopers.domain.point.UserPointRepository;
import com.loopers.domain.product.Price;
import com.loopers.domain.product.ProductService;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class OrderFacadeIntegrationTest {

    private static final Long USER = 1L;
    private static final Long OTHER_USER = 2L;
    private static final Instant NOW = Instant.parse("2026-09-14T00:00:00Z");

    private final OrderFacade orderFacade;
    private final ProductService productService;
    private final PointService pointService;
    private final PointFacade pointFacade;
    private final UserPointRepository userPointRepository;
    private final DatabaseCleanUp databaseCleanUp;
    private final BrandFacade brandFacade;
    private final ProductFacade productFacade;

    private Long productId;

    @Autowired
    OrderFacadeIntegrationTest(
        OrderFacade orderFacade,
        ProductService productService,
        PointService pointService,
        PointFacade pointFacade,
        UserPointRepository userPointRepository,
        DatabaseCleanUp databaseCleanUp,
        BrandFacade brandFacade,
        ProductFacade productFacade
    ) {
        this.orderFacade = orderFacade;
        this.productService = productService;
        this.pointService = pointService;
        this.pointFacade = pointFacade;
        this.userPointRepository = userPointRepository;
        this.databaseCleanUp = databaseCleanUp;
        this.brandFacade = brandFacade;
        this.productFacade = productFacade;
    }

    @BeforeEach
    void setUp() {
        Long brandId = brandFacade.register("무신사", "패션 플랫폼").getId();
        productId = productFacade.register(brandId, "코트", Price.of(10_000)).getId();
        productFacade.adjustStock(productId, Quantity.of(10));
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private OrderCreateCommand command(Long userId, int quantity) {
        return new OrderCreateCommand(userId,
            List.of(new OrderCreateCommand.Line(productId, OrderQuantity.of(quantity))));
    }

    @Nested
    @DisplayName("접수 — ORDER-003 · 005 · 007")
    class Place {
        @DisplayName("접수하면 DRAFT 주문이 생기고 재고·잔액은 그대로다")
        @Test
        void placesWithoutDeducting() {
            pointFacade.charge(USER, ChargeAmount.of(50_000), NOW);

            Order order = orderFacade.place(command(USER, 2), NOW);

            assertThat(order.getStatus()).isEqualTo(OrderStatus.DRAFT);
            assertThat(order.getTotalAmount()).isEqualTo(Money.of(20_000));
            assertThat(productService.getStock(productId)).isEqualTo(Quantity.of(10));
            assertThat(pointService.getBalance(USER)).isEqualTo(Money.of(50_000));
        }

        @DisplayName("ORDER-003 · 삭제된 상품은 접수할 수 없다")
        @Test
        void rejectsDeletedProduct() {
            productFacade.delete(productId);

            assertThatThrownBy(() -> orderFacade.place(command(USER, 1), NOW))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("error", DomainError.PRODUCT_NOT_FOUND);
        }

        @DisplayName("ORDER-005 · 022 · 접수 후 상품의 가격·이름이 바뀌어도 주문은 그대로다")
        @Test
        void copiesPriceAndNameAtPlacement() {
            Order order = orderFacade.place(command(USER, 2), NOW);

            productFacade.update(productId, "트렌치코트", Price.of(99_000));

            Order found = orderFacade.get(USER, order.getId());
            assertThat(found.getTotalAmount()).isEqualTo(Money.of(20_000));
            assertThat(found.getItems().get(0).productName()).isEqualTo("코트");
            assertThat(found.getItems().get(0).unitPrice()).isEqualTo(Price.of(10_000));
        }

        @DisplayName("ORDER-022 · 접수 후 상품이 삭제되어도 주문 내역의 이름은 남는다")
        @Test
        void keepsNameAfterProductDeleted() {
            Order order = orderFacade.place(command(USER, 1), NOW);

            productFacade.delete(productId);

            assertThat(orderFacade.get(USER, order.getId()).getItems().get(0).productName())
                .isEqualTo("코트");
        }
    }

    @Nested
    @DisplayName("확정 — ORDER-012 ~ 018")
    class Confirm {
        @DisplayName("확정하면 재고와 포인트가 함께 차감된다")
        @Test
        void deductsBoth() {
            pointFacade.charge(USER, ChargeAmount.of(50_000), NOW);
            Order order = orderFacade.place(command(USER, 2), NOW);

            orderFacade.confirm(USER, order.getId(), NOW);

            assertThat(productService.getStock(productId)).isEqualTo(Quantity.of(8));
            assertThat(pointService.getBalance(USER)).isEqualTo(Money.of(30_000));
            assertThat(orderFacade.get(USER, order.getId()).getStatus()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(orderFacade.get(USER, order.getId()).getPaidAmount()).isEqualTo(Money.of(20_000));
        }

        @DisplayName("ORDER-013 · 재고가 모자라면 확정할 수 없다")
        @Test
        void rejectsWhenStockIsShort() {
            pointFacade.charge(USER, ChargeAmount.of(500_000), NOW);
            Order order = orderFacade.place(command(USER, 20), NOW);

            assertThatThrownBy(() -> orderFacade.confirm(USER, order.getId(), NOW))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("error", DomainError.INSUFFICIENT_STOCK);
        }

        @DisplayName("ORDER-015 · 잔액이 부족하면 재고도 차감되지 않는다 — 전부 아니면 전무")
        @Test
        void rollsBackStockWhenPointIsShort() {
            pointFacade.charge(USER, ChargeAmount.of(10_000), NOW);
            Order order = orderFacade.place(command(USER, 2), NOW);

            assertThatThrownBy(() -> orderFacade.confirm(USER, order.getId(), NOW))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("error", DomainError.INSUFFICIENT_POINT);

            assertThat(productService.getStock(productId)).isEqualTo(Quantity.of(10));
            assertThat(pointService.getBalance(USER)).isEqualTo(Money.of(10_000));
            assertThat(orderFacade.get(USER, order.getId()).getStatus()).isEqualTo(OrderStatus.DRAFT);
        }

        @DisplayName("ORDER-012 · 접수 후 상품이 삭제되면 확정할 수 없다")
        @Test
        void rejectsWhenProductDeletedAfterPlacement() {
            pointFacade.charge(USER, ChargeAmount.of(50_000), NOW);
            Order order = orderFacade.place(command(USER, 1), NOW);
            productFacade.delete(productId);

            assertThatThrownBy(() -> orderFacade.confirm(USER, order.getId(), NOW))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("error", DomainError.PRODUCT_NOT_FOUND);

            assertThat(pointService.getBalance(USER)).isEqualTo(Money.of(50_000));
        }

        @DisplayName("ORDER-010 · 이미 확정된 주문은 다시 확정되지 않는다. 차감도 한 번뿐이다")
        @Test
        void rejectsSecondConfirm() {
            pointFacade.charge(USER, ChargeAmount.of(50_000), NOW);
            Order order = orderFacade.place(command(USER, 2), NOW);
            orderFacade.confirm(USER, order.getId(), NOW);

            assertThatThrownBy(() -> orderFacade.confirm(USER, order.getId(), NOW))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("error", DomainError.ORDER_NOT_DRAFT);

            assertThat(productService.getStock(productId)).isEqualTo(Quantity.of(8));
            assertThat(pointService.getBalance(USER)).isEqualTo(Money.of(30_000));
        }

        @DisplayName("ORDER-011 · 남의 주문은 확정할 수 없다. 존재를 숨겨 404 다")
        @Test
        void rejectsForeignOrder() {
            pointFacade.charge(USER, ChargeAmount.of(50_000), NOW);
            Order order = orderFacade.place(command(USER, 1), NOW);

            assertThatThrownBy(() -> orderFacade.confirm(OTHER_USER, order.getId(), NOW))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("error", DomainError.ORDER_NOT_FOUND);
            assertThatThrownBy(() -> orderFacade.get(OTHER_USER, order.getId()))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("error", DomainError.ORDER_NOT_FOUND);
        }

        @DisplayName("ORDER-018 · 같은 주문에 확정이 동시에 들어와도 한 번만 확정되고 차감도 한 번이다")
        @Test
        void confirmsOnlyOnceUnderConcurrency() throws InterruptedException {
            pointFacade.charge(USER, ChargeAmount.of(50_000), NOW);
            Order order = orderFacade.place(command(USER, 2), NOW);

            int threads = 10;
            ExecutorService executor = Executors.newFixedThreadPool(threads);
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);
            AtomicInteger succeeded = new AtomicInteger();

            for (int i = 0; i < threads; i++) {
                executor.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        orderFacade.confirm(USER, order.getId(), NOW);
                        succeeded.incrementAndGet();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (RuntimeException e) {
                    } finally {
                        done.countDown();
                    }
                });
            }

            ready.await(5, TimeUnit.SECONDS);
            start.countDown();
            done.await(30, TimeUnit.SECONDS);
            executor.shutdown();

            assertThat(succeeded.get()).isEqualTo(1);
            assertThat(productService.getStock(productId)).isEqualTo(Quantity.of(8));
            assertThat(pointService.getBalance(USER)).isEqualTo(Money.of(30_000));
        }
    }

    @Nested
    @DisplayName("일관성 — 잔액은 원장과 어긋나지 않는다")
    class LedgerConsistency {
        @DisplayName("확정이 원장에 USE 한 줄을 남기고, 잔액은 마지막 줄의 balanceAfter 와 같다")
        @Test
        void keepsBalanceConsistentWithLedger() {
            pointFacade.charge(USER, ChargeAmount.of(50_000), NOW);
            Order order = orderFacade.place(command(USER, 2), NOW);

            orderFacade.confirm(USER, order.getId(), NOW);

            List<PointTransaction> ledger = userPointRepository.findTransactions(USER);
            PointTransaction last = ledger.get(ledger.size() - 1);

            assertThat(ledger).extracting(PointTransaction::type)
                .containsExactly(TransactionType.CHARGE, TransactionType.USE);
            assertThat(last.amount()).isEqualTo(Money.of(20_000));
            assertThat(last.balanceAfter()).isEqualTo(pointService.getBalance(USER));
        }

        @DisplayName("거절된 확정은 원장에 아무 줄도 남기지 않는다 — 롤백이 기록까지 되돌린다")
        @Test
        void rejectedConfirmWritesNoLedgerLine() {
            pointFacade.charge(USER, ChargeAmount.of(10_000), NOW);
            Order order = orderFacade.place(command(USER, 2), NOW);

            assertThatThrownBy(() -> orderFacade.confirm(USER, order.getId(), NOW))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("error", DomainError.INSUFFICIENT_POINT);

            assertThat(userPointRepository.findTransactions(USER))
                .extracting(PointTransaction::type)
                .containsExactly(TransactionType.CHARGE);
        }
    }
}
