package com.loopers.application.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;

import com.loopers.application.brand.fixture.BrandFixture;
import com.loopers.application.order.fixture.OrderFixture;
import com.loopers.application.point.fixture.PointFixture;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.order.PaymentResult;
import com.loopers.domain.point.PointBalanceRepository;
import com.loopers.domain.product.Product;
import com.loopers.infrastructure.product.fixture.ProductFixture;
import com.loopers.infrastructure.user.fixture.UserFixture;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.utils.DatabaseCleanUp;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.List;

@SpringBootTest
class OrderIntegrationTest {
    @Autowired private ConfirmOrderFacade confirmOrder;
    @Autowired private BrandFixture brands;
    @Autowired private OrderFixture orders;
    @Autowired private ProductFixture products;
    @Autowired private PointFixture points;
    @Autowired private UserFixture users;
    @Autowired private DatabaseCleanUp cleanUp;
    @MockitoSpyBean private PointBalanceRepository pointRepository;

    @Test
    void 여러_품목의_주문을_확정하면_재고와_포인트와_결제_결과가_함께_저장된다() {
        // arrange
        users.createUser(1);
        Brand brand = brands.createBrand();
        Product first = products.createProduct(brand.getId(), "첫 상품", 2_000, 5);
        Product second = products.createProduct(brand.getId(), "두 번째 상품", 1_000, 5);
        points.createBalance(1, 10_000);
        Order draft = createOrderWithTwoItems(first, 2, second, 3);

        // act
        OrderInfo result = confirmOrder.confirm(1L, draft.getId());

        // assert
        assertThat(result.status()).isEqualTo(OrderStatus.CONFIRMED.name());
        assertThat(products.product(first.getId()).getStock()).isEqualTo(3);
        assertThat(products.product(second.getId()).getStock()).isEqualTo(2);
        assertThat(points.balance(1)).isEqualTo(3_000);
        Order stored = orders.order(draft.getId());
        assertThat(stored.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(stored.getPaidAmount()).isEqualTo(7_000);
        assertThat(stored.getPaymentResult()).isEqualTo(PaymentResult.SUCCESS);
    }

    @Test
    void 두_번째_품목의_재고가_부족하면_앞선_차감과_확정을_롤백한다() {
        // arrange
        users.createUser(1);
        Brand brand = brands.createBrand();
        Product first = products.createProduct(brand.getId(), "첫 상품", 2_000, 5);
        Product second = products.createProduct(brand.getId(), "두 번째 상품", 1_000, 1);
        points.createBalance(1, 10_000);
        Order draft = createOrderWithTwoItems(first, 2, second, 3);

        // act
        CoreException error =
                assertThrows(CoreException.class, () -> confirmOrder.confirm(1L, draft.getId()));

        // assert
        assertThat(error.getErrorType()).isEqualTo(ErrorType.INSUFFICIENT_STOCK);
        assertThat(products.product(first.getId()).getStock()).isEqualTo(5);
        assertThat(products.product(second.getId()).getStock()).isEqualTo(1);
        assertThat(points.balance(1)).isEqualTo(10_000);
        Order stored = orders.order(draft.getId());
        assertThat(stored.getStatus()).isEqualTo(OrderStatus.DRAFT);
        assertThat(stored.getPaidAmount()).isNull();
        assertThat(stored.getPaymentResult()).isNull();
        assertThat(stored.getTotalAmount()).isEqualTo(7_000);
    }

    @Test
    void 포인트가_부족하면_모든_품목의_차감과_확정을_롤백한다() {
        // arrange
        users.createUser(1);
        Brand brand = brands.createBrand();
        Product first = products.createProduct(brand.getId(), "첫 상품", 2_000, 5);
        Product second = products.createProduct(brand.getId(), "두 번째 상품", 1_000, 5);
        points.createBalance(1, 6_000);
        Order draft = createOrderWithTwoItems(first, 2, second, 3);

        // act
        CoreException error =
                assertThrows(CoreException.class, () -> confirmOrder.confirm(1L, draft.getId()));

        // assert
        assertThat(error.getErrorType()).isEqualTo(ErrorType.INSUFFICIENT_POINTS);
        assertThat(products.product(first.getId()).getStock()).isEqualTo(5);
        assertThat(products.product(second.getId()).getStock()).isEqualTo(5);
        assertThat(points.balance(1)).isEqualTo(6_000);
        Order stored = orders.order(draft.getId());
        assertThat(stored.getStatus()).isEqualTo(OrderStatus.DRAFT);
        assertThat(stored.getPaidAmount()).isNull();
        assertThat(stored.getPaymentResult()).isNull();
        assertThat(stored.getTotalAmount()).isEqualTo(7_000);
    }

    @Test
    void 포인트_저장_경계에서_실패하면_주문과_재고_변경을_롤백한다() {
        // arrange
        users.createUser(1);
        Brand brand = brands.createBrand();
        Product first = products.createProduct(brand.getId(), "첫 상품", 2_000, 5);
        Product second = products.createProduct(brand.getId(), "두 번째 상품", 1_000, 5);
        points.createBalance(1, 10_000);
        Order draft = createOrderWithTwoItems(first, 2, second, 3);
        DataAccessException failure = new DataAccessResourceFailureException("포인트 차감 실패");
        // 주문 확정과 재고 차감 SQL 다음 저장 경계에서 실패를 유발한다.
        doThrow(failure).when(pointRepository).deduct(1, draft.getTotalAmount());

        // act
        DataAccessException error =
                assertThrows(
                        DataAccessException.class, () -> confirmOrder.confirm(1L, draft.getId()));

        // assert: 서비스 실패 후 별도 트랜잭션에서 다시 조회한다.
        assertThat(error).isSameAs(failure);
        assertThat(products.product(first.getId()).getStock()).isEqualTo(5);
        assertThat(products.product(second.getId()).getStock()).isEqualTo(5);
        assertThat(points.balance(1)).isEqualTo(10_000);
        Order stored = orders.order(draft.getId());
        assertThat(stored.getStatus()).isEqualTo(OrderStatus.DRAFT);
        assertThat(stored.getPaidAmount()).isNull();
        assertThat(stored.getPaymentResult()).isNull();
        assertThat(stored.getTotalAmount()).isEqualTo(7_000);
    }

    private Order createOrderWithTwoItems(
            Product first, int firstQuantity, Product second, int secondQuantity) {
        return orders.createOrder(
                1,
                List.of(
                        new Order.RequestedItem(first.getId(), firstQuantity, first.getPrice()),
                        new Order.RequestedItem(second.getId(), secondQuantity, second.getPrice())));
    }

    @AfterEach
    void cleanDatabase() {
        cleanUp.deleteAllEntities();
    }
}
