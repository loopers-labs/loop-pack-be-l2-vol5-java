package com.loopers.domain.order;

import com.loopers.domain.common.ListSort;
import com.loopers.domain.common.PageCommand;
import com.loopers.domain.common.PageResult;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.user.UserModel;
import com.loopers.fixture.ProductFixture;
import com.loopers.fixture.UserFixture;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

@DisplayName("OrderService 는 내 주문 목록을 Order 단위로 페이지 조회한다.")
@SpringBootTest
class OrderListIntegrationTest {

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

    @DisplayName("소유 범위")
    @Nested
    class Ownership {
        @DisplayName("다른 사용자의 주문은 목록에 포함하지 않는다.")
        @Test
        void returnsOnlyOwnOrders() {
            UserModel me = userFixture.createUserWithPoint();
            UserModel other = userFixture.createUserWithPoint();
            ProductModel shirt = productFixture.createProduct("티셔츠", 1_000L, 100L);
            OrderModel mine = order(me, shirt, 1L);
            order(other, shirt, 1L);

            PageResult<OrderModel> result = orderService.getOrders(
                me.getId(), PageCommand.of(null, null), ListSort.LATEST);

            assertAll(
                () -> assertThat(result.totalElements()).isEqualTo(1L),
                () -> assertThat(result.items()).extracting(OrderModel::getId).containsExactly(mine.getId())
            );
        }
    }

    @DisplayName("품목 포함")
    @Nested
    class Items {
        @DisplayName("품목이 여러 개인 주문의 모든 OrderItem 을 함께 반환한다.")
        @Test
        void includesAllItems() {
            UserModel user = userFixture.createUserWithPoint();
            ProductModel shirt = productFixture.createProduct("티셔츠", 1_000L, 100L);
            ProductModel pants = productFixture.createProduct("바지", 2_000L, 100L);
            orderService.create(user.getId(), List.of(
                new OrderItemCommand(shirt.getId(), 1L),
                new OrderItemCommand(pants.getId(), 2L)
            ));

            PageResult<OrderModel> result = orderService.getOrders(
                user.getId(), PageCommand.of(null, null), ListSort.LATEST);

            assertAll(
                () -> assertThat(result.totalElements()).isEqualTo(1L),
                () -> assertThat(result.items().get(0).getItems()).hasSize(2)
            );
        }
    }

    @DisplayName("정렬과 페이지")
    @Nested
    class Paging {
        @DisplayName("기본 정렬 latest 는 최근 주문부터 반환한다.")
        @Test
        void sortsByLatest() {
            UserModel user = userFixture.createUserWithPoint();
            ProductModel shirt = productFixture.createProduct("티셔츠", 1_000L, 100L);
            OrderModel first = order(user, shirt, 1L);
            OrderModel second = order(user, shirt, 1L);
            OrderModel third = order(user, shirt, 1L);

            PageResult<OrderModel> result = orderService.getOrders(
                user.getId(), PageCommand.of(null, null), ListSort.LATEST);

            assertThat(result.items()).extracting(OrderModel::getId)
                .containsExactly(third.getId(), second.getId(), first.getId());
        }

        @DisplayName("oldest 는 먼저 만든 주문부터 반환한다.")
        @Test
        void sortsByOldest() {
            UserModel user = userFixture.createUserWithPoint();
            ProductModel shirt = productFixture.createProduct("티셔츠", 1_000L, 100L);
            OrderModel first = order(user, shirt, 1L);
            OrderModel second = order(user, shirt, 1L);
            OrderModel third = order(user, shirt, 1L);

            PageResult<OrderModel> result = orderService.getOrders(
                user.getId(), PageCommand.of(null, null), ListSort.OLDEST);

            assertThat(result.items()).extracting(OrderModel::getId)
                .containsExactly(first.getId(), second.getId(), third.getId());
        }

        @DisplayName("page=0, size=2, oldest 는 앞의 두 건과 전체 자원 수를 반환한다.")
        @Test
        void returnsFirstPageWithTotalElements() {
            UserModel user = userFixture.createUserWithPoint();
            ProductModel shirt = productFixture.createProduct("티셔츠", 1_000L, 100L);
            OrderModel first = order(user, shirt, 1L);
            OrderModel second = order(user, shirt, 1L);
            order(user, shirt, 1L);

            PageResult<OrderModel> result = orderService.getOrders(
                user.getId(), PageCommand.of(0, 2), ListSort.OLDEST);

            assertAll(
                () -> assertThat(result.items()).extracting(OrderModel::getId)
                    .containsExactly(first.getId(), second.getId()),
                () -> assertThat(result.page()).isZero(),
                () -> assertThat(result.size()).isEqualTo(2),
                () -> assertThat(result.totalElements()).isEqualTo(3L),
                () -> assertThat(result.totalPages()).isEqualTo(2)
            );
        }

        @DisplayName("마지막 페이지는 남은 건만 반환한다.")
        @Test
        void returnsLastPage() {
            UserModel user = userFixture.createUserWithPoint();
            ProductModel shirt = productFixture.createProduct("티셔츠", 1_000L, 100L);
            order(user, shirt, 1L);
            order(user, shirt, 1L);
            OrderModel third = order(user, shirt, 1L);

            PageResult<OrderModel> result = orderService.getOrders(
                user.getId(), PageCommand.of(1, 2), ListSort.OLDEST);

            assertThat(result.items()).extracting(OrderModel::getId).containsExactly(third.getId());
        }

        @DisplayName("주문이 없으면 빈 페이지를 반환한다.")
        @Test
        void returnsEmptyPage() {
            UserModel user = userFixture.createUserWithPoint();

            PageResult<OrderModel> result = orderService.getOrders(
                user.getId(), PageCommand.of(null, null), ListSort.LATEST);

            assertAll(
                () -> assertThat(result.items()).isEmpty(),
                () -> assertThat(result.totalElements()).isZero(),
                () -> assertThat(result.totalPages()).isZero()
            );
        }
    }
}
