package com.loopers.application.order;

import com.loopers.application.order.dto.OrderInfo;
import com.loopers.application.order.dto.OrderItemCommand;
import com.loopers.application.order.dto.OrderItemInfo;
import com.loopers.application.order.dto.OrderSummaryInfo;
import com.loopers.application.order.dto.PaymentInfo;
import com.loopers.domain.common.Money;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.product.Stock;
import com.loopers.domain.user.User;
import com.loopers.domain.user.UserRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.support.fixture.ConfirmedOrders;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
class AdminOrderFacadeTest {

    @Autowired
    private AdminOrderFacade adminOrderFacade;

    @Autowired
    private OrderFacade orderFacade;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private User saveUser() {
        User user = User.create();
        user.chargePoint(new Money(10_000));
        return userRepository.save(user);
    }

    private Product saveProduct(String name, long price) {
        return productRepository.save(Product.create(1L, name, new Money(price), new Stock(10)));
    }

    private Long placeOrder(User user, Product product, int quantity) {
        return orderFacade.create(user.getId(), List.of(new OrderItemCommand(product.getId(), quantity))).id();
    }

    @DisplayName("관리자가 주문 목록을 조회할 때,")
    @Nested
    class GetList {
        @DisplayName("구매자를 지정하지 않으면 모든 구매자의 주문을 최신순으로, 구매자 id 와 함께 돌려준다.")
        @Test
        void returnsAllOrders_newestFirst_withUserId() {
            // arrange
            User first = saveUser();
            User second = saveUser();
            Product shirt = saveProduct("티셔츠", 3_000);
            Long firstOrder = placeOrder(first, shirt, 1);
            Long secondOrder = placeOrder(second, shirt, 2);

            // act
            Page<OrderSummaryInfo> page = adminOrderFacade.getList(null, 0, 20);

            // assert
            assertThat(page.getContent()).extracting(OrderSummaryInfo::id).containsExactly(secondOrder, firstOrder);
            assertThat(page.getContent()).extracting(OrderSummaryInfo::userId)
                .containsExactly(second.getId(), first.getId());
            assertThat(page.getContent().get(0).orderedAt()).isNotNull();
        }

        @DisplayName("구매자를 지정하면 그 구매자의 주문만 돌려주고, 주문이 없거나 없는 구매자는 오류가 아니라 빈 목록이다.")
        @Test
        void filtersByUser_andReturnsEmpty_whenNoOrders() {
            // arrange
            User buyer = saveUser();
            User other = saveUser();
            Product shirt = saveProduct("티셔츠", 3_000);
            Long buyersOrder = placeOrder(buyer, shirt, 1);
            placeOrder(other, shirt, 1);

            // act
            Page<OrderSummaryInfo> page = adminOrderFacade.getList(buyer.getId(), 0, 20);

            // assert
            assertThat(page.getContent()).extracting(OrderSummaryInfo::id).containsExactly(buyersOrder);
            assertThat(adminOrderFacade.getList(999_999L, 0, 20).getContent()).isEmpty();
        }

        @DisplayName("잘못된 page·size 는 INVALID_INPUT 이다.")
        @Test
        void throwsInvalidInput_whenPageOrSizeIsOutOfRange() {
            CoreException exception = assertThrows(CoreException.class, () -> adminOrderFacade.getList(null, 0, 0));

            assertThat(exception.getErrorType()).isEqualTo(ErrorType.INVALID_INPUT);
        }
    }

    @DisplayName("관리자가 주문 하나를 조회할 때,")
    @Nested
    class Get {
        @DisplayName("누구의 주문이든 품목·결제 결과·구매자 id·주문 시각과 함께 돌려주고, 없는 주문은 NOT_FOUND 이다.")
        @Test
        void returnsAnyOrder_orThrowsNotFound() {
            // arrange
            User buyer = saveUser();
            Product shirt = saveProduct("티셔츠", 3_000);
            Long orderId = placeOrder(buyer, shirt, 2);
            ConfirmedOrders.confirm(jdbcTemplate, orderId);

            // act
            OrderInfo info = adminOrderFacade.get(orderId);
            CoreException exception = assertThrows(CoreException.class, () -> adminOrderFacade.get(999_999L));

            // assert
            assertThat(info.userId()).isEqualTo(buyer.getId());
            assertThat(info.status()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(info.items()).containsExactly(new OrderItemInfo(shirt.getId(), "티셔츠", 2, 3_000L, 6_000L));
            assertThat(info.payment()).isEqualTo(new PaymentInfo(6_000L));
            assertThat(info.orderedAt()).isNotNull();
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
        }
    }
}
