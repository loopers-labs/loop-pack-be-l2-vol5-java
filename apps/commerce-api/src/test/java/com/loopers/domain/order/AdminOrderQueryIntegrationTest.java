package com.loopers.domain.order;

import com.loopers.domain.common.ListSort;
import com.loopers.domain.common.PageCommand;
import com.loopers.domain.common.PageResult;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.user.UserModel;
import com.loopers.fixture.ProductFixture;
import com.loopers.fixture.UserFixture;
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

@DisplayName("OrderService 는 관리자에게 소유자와 무관한 전체 주문을 조회해 준다.")
@SpringBootTest
class AdminOrderQueryIntegrationTest {

    @Autowired
    private OrderService orderService;
    @Autowired
    private UserFixture userFixture;
    @Autowired
    private ProductFixture productFixture;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private OrderModel order(UserModel user, ProductModel product, long quantity) {
        return orderService.create(user.getId(), List.of(new OrderItemCommand(product.getId(), quantity)));
    }

    @DisplayName("전체 주문 목록")
    @Nested
    class GetAllOrders {
        @DisplayName("소유자와 상관없이 모든 주문을 최신순으로 반환한다.")
        @Test
        void returnsEveryOrder() {
            UserModel first = userFixture.createUserWithPoint();
            UserModel second = userFixture.createUserWithPoint();
            ProductModel shoes = productFixture.createProduct("운동화", 10_000L, 100L);
            OrderModel firstOrder = order(first, shoes, 1L);
            OrderModel secondOrder = order(second, shoes, 2L);

            PageResult<OrderModel> result = orderService.getAllOrders(PageCommand.of(null, null), ListSort.LATEST);

            assertAll(
                () -> assertThat(result.totalElements()).isEqualTo(2L),
                () -> assertThat(result.items()).extracting(OrderModel::getId)
                    .containsExactly(secondOrder.getId(), firstOrder.getId()),
                () -> assertThat(result.items()).extracting(OrderModel::getUserId)
                    .containsExactly(second.getId(), first.getId())
            );
        }

        @DisplayName("oldest 는 오래된 주문부터 페이지를 끊어 반환한다.")
        @Test
        void returnsOldestPage() {
            UserModel user = userFixture.createUserWithPoint();
            ProductModel shoes = productFixture.createProduct("운동화", 10_000L, 100L);
            OrderModel first = order(user, shoes, 1L);
            OrderModel second = order(user, shoes, 2L);
            order(user, shoes, 3L);

            PageResult<OrderModel> result = orderService.getAllOrders(PageCommand.of(0, 2), ListSort.OLDEST);

            assertAll(
                () -> assertThat(result.totalElements()).isEqualTo(3L),
                () -> assertThat(result.totalPages()).isEqualTo(2),
                () -> assertThat(result.items()).extracting(OrderModel::getId)
                    .containsExactly(first.getId(), second.getId())
            );
        }

        @DisplayName("주문마다 그 주문의 모든 품목을 포함한다.")
        @Test
        void includesAllItemsOfEachOrder() {
            UserModel user = userFixture.createUserWithPoint();
            ProductModel shoes = productFixture.createProduct("운동화", 10_000L, 100L);
            ProductModel cap = productFixture.createProduct("모자", 5_000L, 100L);
            orderService.create(user.getId(), List.of(
                new OrderItemCommand(shoes.getId(), 1L),
                new OrderItemCommand(cap.getId(), 2L)
            ));

            PageResult<OrderModel> result = orderService.getAllOrders(PageCommand.of(null, null), ListSort.LATEST);

            assertThat(result.items().get(0).getItems()).hasSize(2);
        }

        @DisplayName("주문이 없으면 빈 페이지를 반환한다.")
        @Test
        void returnsEmptyPage() {
            PageResult<OrderModel> result = orderService.getAllOrders(PageCommand.of(null, null), ListSort.LATEST);

            assertAll(
                () -> assertThat(result.items()).isEmpty(),
                () -> assertThat(result.totalElements()).isZero()
            );
        }
    }

    @DisplayName("주문 상세")
    @Nested
    class GetAnyOrder {
        @DisplayName("소유자를 확인하지 않고 주문과 품목을 반환한다.")
        @Test
        void returnsOrderOfAnyUser() {
            UserModel user = userFixture.createUserWithPoint();
            ProductModel shoes = productFixture.createProduct("운동화", 10_000L, 100L);
            OrderModel created = order(user, shoes, 2L);

            OrderModel found = orderService.getAnyOrder(created.getId());

            assertAll(
                () -> assertThat(found.getId()).isEqualTo(created.getId()),
                () -> assertThat(found.getUserId()).isEqualTo(user.getId()),
                () -> assertThat(found.getItems()).hasSize(1),
                () -> assertThat(found.getStatus()).isEqualTo(OrderStatus.DRAFT)
            );
        }

        @DisplayName("존재하지 않는 주문은 ORDER_NOT_FOUND 로 거절한다.")
        @Test
        void rejectsUnknownOrder() {
            assertThatThrownBy(() -> orderService.getAnyOrder(999999L))
                .isInstanceOf(CoreException.class)
                .hasFieldOrPropertyWithValue("errorType", ErrorType.ORDER_NOT_FOUND);
        }
    }
}
