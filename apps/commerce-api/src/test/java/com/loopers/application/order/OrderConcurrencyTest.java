package com.loopers.application.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.loopers.application.brand.fixture.BrandFixture;
import com.loopers.application.order.fixture.OrderFixture;
import com.loopers.application.point.ChargePointFacade;
import com.loopers.application.point.fixture.PointFixture;
import com.loopers.application.product.SetStockProductFacade;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.order.PaymentResult;
import com.loopers.domain.product.Product;
import com.loopers.infrastructure.product.fixture.ProductFixture;
import com.loopers.infrastructure.user.fixture.UserFixture;
import com.loopers.support.ConcurrentRequests;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.utils.DatabaseCleanUp;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

@SpringBootTest
class OrderConcurrencyTest {
    @Autowired private ConfirmOrderFacade confirmOrder;
    @Autowired private ChargePointFacade chargePoint;
    @Autowired private SetStockProductFacade setStock;
    @Autowired private BrandFixture brands;
    @Autowired private OrderFixture orders;
    @Autowired private ProductFixture products;
    @Autowired private PointFixture points;
    @Autowired private UserFixture users;
    @Autowired private DatabaseCleanUp cleanUp;

    @Test
    void 마지막_재고를_동시에_주문하면_하나의_주문만_확정된다() throws Exception {
        // arrange
        Brand brand = brands.createBrand();
        Product product = products.createProduct(brand.getId(), "상품", 1_000, 1);
        List<Order> drafts = createOrdersForDifferentUsers(product, 2, 2_000);

        // act
        var result = ConcurrentRequests.run(confirmationRequests(drafts));

        // assert
        assertThat(result.successes()).hasSize(1);
        assertThat(result.failures()).hasSize(1);
        CoreException rejection =
                assertInstanceOf(CoreException.class, result.failures().getFirst());
        assertThat(rejection.getErrorType()).isEqualTo(ErrorType.INSUFFICIENT_STOCK);
        assertThat(products.product(product.getId()).getStock()).isZero();
        assertThat(orders.orders(drafts))
                .extracting(Order::getStatus)
                .containsExactlyInAnyOrder(OrderStatus.CONFIRMED, OrderStatus.DRAFT);
    }

    @Test
    void 재고_5개를_8개_주문이_경쟁하면_성공_수량과_최종_재고가_일치한다() throws Exception {
        // arrange
        Brand brand = brands.createBrand();
        Product product = products.createProduct(brand.getId(), "상품", 1_000, 5);
        List<Order> drafts = createOrdersForDifferentUsers(product, 8, 2_000);

        // act
        var result = ConcurrentRequests.run(confirmationRequests(drafts));

        // assert
        List<Order> stored = orders.orders(drafts);
        List<Order> confirmed = ordersWithStatus(stored, OrderStatus.CONFIRMED);
        List<Order> rejected = ordersWithStatus(stored, OrderStatus.DRAFT);
        List<Long> successfulIds = result.successes().stream().map(OrderInfo::orderId).toList();

        assertThat(result.successes()).hasSize(5);
        assertThat(result.failures()).hasSize(3);
        for (Throwable failure : result.failures()) {
            CoreException rejection = assertInstanceOf(CoreException.class, failure);
            assertThat(rejection.getErrorType()).isEqualTo(ErrorType.INSUFFICIENT_STOCK);
        }

        assertThat(confirmed)
                .extracting(Order::getId)
                .containsExactlyInAnyOrderElementsOf(successfulIds);
        assertThat(confirmed).extracting(Order::getPaidAmount).containsOnly(1_000L);
        assertThat(confirmed).extracting(Order::getPaymentResult).containsOnly(PaymentResult.SUCCESS);
        assertThat(balancesOf(confirmed)).containsOnly(1_000L);

        assertThat(rejected).hasSize(3);
        assertThat(rejected).extracting(Order::getPaidAmount).containsOnlyNulls();
        assertThat(rejected).extracting(Order::getPaymentResult).containsOnlyNulls();
        assertThat(balancesOf(rejected)).containsOnly(2_000L);

        long purchased = totalQuantity(confirmed);
        int remainingStock = products.product(product.getId()).getStock();
        assertThat(remainingStock).isZero();
        assertThat(5 - purchased).isEqualTo(remainingStock);

        long paid = confirmed.stream().mapToLong(Order::getPaidAmount).sum();
        long remainingPoints = balancesOf(stored).stream().mapToLong(Long::longValue).sum();
        assertThat(8 * 2_000 - paid).isEqualTo(remainingPoints);
    }

    @Test
    void 같은_잔액으로_4000원_주문_3개를_확정하면_2개만_성공한다() throws Exception {
        // arrange
        users.createUser(1);
        points.createBalance(1, 10_000);
        Brand brand = brands.createBrand();
        List<Order> drafts = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            Product product = products.createProduct(brand.getId(), "상품 " + index, 4_000, 5);
            drafts.add(orders.createOrder(1, product, 1));
        }

        // act
        var result = ConcurrentRequests.run(confirmationRequests(drafts));

        // assert
        List<Order> stored = orders.orders(drafts);
        List<Order> confirmed = ordersWithStatus(stored, OrderStatus.CONFIRMED);
        List<Order> rejected = ordersWithStatus(stored, OrderStatus.DRAFT);
        List<Long> successfulIds = result.successes().stream().map(OrderInfo::orderId).toList();

        assertThat(result.successes()).hasSize(2);
        assertThat(result.failures()).hasSize(1);
        CoreException rejection =
                assertInstanceOf(CoreException.class, result.failures().getFirst());
        assertThat(rejection.getErrorType()).isEqualTo(ErrorType.INSUFFICIENT_POINTS);

        assertThat(confirmed)
                .extracting(Order::getId)
                .containsExactlyInAnyOrderElementsOf(successfulIds);
        assertThat(confirmed).extracting(Order::getPaidAmount).containsOnly(4_000L);
        assertThat(confirmed).extracting(Order::getPaymentResult).containsOnly(PaymentResult.SUCCESS);
        assertThat(stocksForSingleItemOrders(confirmed)).containsOnly(4);

        assertThat(rejected).hasSize(1);
        assertThat(rejected).extracting(Order::getPaidAmount).containsOnlyNulls();
        assertThat(rejected).extracting(Order::getPaymentResult).containsOnlyNulls();
        assertThat(stocksForSingleItemOrders(rejected)).containsOnly(5);

        long paid = confirmed.stream().mapToLong(Order::getPaidAmount).sum();
        long remainingPoints = points.balance(1);
        assertThat(remainingPoints).isEqualTo(2_000);
        assertThat(10_000 - paid).isEqualTo(remainingPoints);

        long purchased = totalQuantity(confirmed);
        long remainingStock =
                stocksForSingleItemOrders(stored).stream().mapToLong(Integer::longValue).sum();
        assertThat(3 * 5 - purchased).isEqualTo(remainingStock);
    }

    @Test
    void 충전과_결제를_동시에_처리하면_두_금액이_잔액에_반영된다() throws Exception {
        // arrange
        users.createUser(1);
        Brand brand = brands.createBrand();
        Product product = products.createProduct(brand.getId(), "상품", 7_000, 1);
        points.createBalance(1, 10_000);
        Order draft = orders.createOrder(1, product, 1);
        List<Callable<Object>> requests =
                List.of(
                        () -> chargePoint.charge(1L, 2_000L),
                        () -> confirmOrder.confirm(1L, draft.getId()));

        // act
        var result = ConcurrentRequests.run(requests);

        // assert
        assertThat(result.successes()).hasSize(2);
        assertThat(result.failures()).isEmpty();
        Order stored = orders.order(draft.getId());
        assertThat(stored.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(stored.getPaidAmount()).isEqualTo(7_000);
        assertThat(stored.getPaymentResult()).isEqualTo(PaymentResult.SUCCESS);
        assertThat(products.product(product.getId()).getStock()).isZero();
        long remainingPoints = points.balance(1);
        assertThat(remainingPoints).isEqualTo(5_000);
        assertThat(10_000 + 2_000 - stored.getPaidAmount()).isEqualTo(remainingPoints);
    }

    @Test
    void 같은_주문을_동시에_확정해도_재고와_포인트는_한_번만_차감한다() throws Exception {
        // arrange
        users.createUser(1);
        Brand brand = brands.createBrand();
        Product product = products.createProduct(brand.getId(), "상품", 2_000, 5);
        points.createBalance(1, 10_000);
        Order draft = orders.createOrder(1, product, 1);

        // act
        var result = ConcurrentRequests.run(confirmationRequests(List.of(draft, draft)));

        // assert
        assertThat(result.successes()).hasSize(1);
        assertThat(result.failures()).hasSize(1);
        CoreException rejection =
                assertInstanceOf(CoreException.class, result.failures().getFirst());
        assertThat(rejection.getErrorType()).isEqualTo(ErrorType.INVALID_REQUEST);
        assertThat(products.product(product.getId()).getStock()).isEqualTo(4);
        assertThat(points.balance(1)).isEqualTo(8_000);
        Order stored = orders.order(draft.getId());
        assertThat(stored.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(stored.getPaidAmount()).isEqualTo(2_000);
        assertThat(stored.getPaymentResult()).isEqualTo(PaymentResult.SUCCESS);
    }

    @Test
    void 관리자_재고_설정과_주문_차감은_DB_처리_순서대로_반영된다() throws Exception {
        // arrange
        users.createUser(1);
        Brand brand = brands.createBrand();
        Product product = products.createProduct(brand.getId(), "상품", 1_000, 5);
        points.createBalance(1, 2_000);
        Order draft = orders.createOrder(1, product, 1);
        List<Callable<Object>> requests =
                List.of(
                        () -> setStock.set(product.getId(), 10),
                        () -> confirmOrder.confirm(1L, draft.getId()));

        // act
        var result = ConcurrentRequests.run(requests);

        // assert
        assertThat(result.successes()).hasSize(2);
        assertThat(result.failures()).isEmpty();
        assertThat(products.product(product.getId()).getStock()).isIn(9, 10);
        assertThat(orders.order(draft.getId()).getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(points.balance(1)).isEqualTo(1_000);
    }

    private List<Order> createOrdersForDifferentUsers(Product product, int count, long balance) {
        List<Order> drafts = new ArrayList<>();
        for (long userId = 1; userId <= count; userId++) {
            users.createUser(userId);
            points.createBalance(userId, balance);
            drafts.add(orders.createOrder(userId, product, 1));
        }
        return drafts;
    }

    private List<Callable<OrderInfo>> confirmationRequests(List<Order> drafts) {
        return drafts.stream()
                .<Callable<OrderInfo>>map(
                        order -> () -> confirmOrder.confirm(order.getUserId(), order.getId()))
                .toList();
    }

    private List<Order> ordersWithStatus(List<Order> stored, OrderStatus status) {
        return stored.stream().filter(order -> order.getStatus() == status).toList();
    }

    private List<Long> balancesOf(List<Order> stored) {
        return stored.stream().map(order -> points.balance(order.getUserId())).toList();
    }

    private List<Integer> stocksForSingleItemOrders(List<Order> stored) {
        return stored.stream()
                .map(order -> products.product(order.getItems().getFirst().getProductId()).getStock())
                .toList();
    }

    private long totalQuantity(List<Order> stored) {
        return stored.stream()
                .flatMap(order -> order.getItems().stream())
                .mapToLong(item -> item.getQuantity())
                .sum();
    }

    @AfterEach
    void cleanDatabase() {
        cleanUp.deleteAllEntities();
    }
}
