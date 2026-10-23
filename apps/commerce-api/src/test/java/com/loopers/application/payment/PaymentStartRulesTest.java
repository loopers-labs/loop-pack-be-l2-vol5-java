package com.loopers.application.payment;

import com.loopers.application.order.dto.OrderItemCommand;
import com.loopers.application.payment.dto.PaymentAttemptInfo;
import com.loopers.application.product.AdminProductFacade;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.payment.PaymentAttemptStatus;
import com.loopers.domain.product.Product;
import com.loopers.domain.user.User;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.support.fixture.ConfirmedOrders;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 결제 시작이 주문 확정 때부터 지켜 온 주문 규칙을 그대로 지키는지 확인한다: 금액은 주문 당시 합계, 소유권, 이미 확정된
 * 주문, 삭제된 상품과 재고 부족, 품목 중 일부만 실패했을 때의 원복, 오류 우선순위. 달라진 점은 포인트다. 결제 시작은 포인트
 * 잔액과 무관하게 시작되고 잔액을 바꾸지 않는다.
 *
 * <p>거절되는 요청은 어떤 것도 바꾸지 않고 PG 를 호출하지 않아야 한다. 외부 호출 0회와 시도 0건으로 함께 확인한다.
 */
class PaymentStartRulesTest extends PaymentFlowTestSupport {

    private static final String KEY = "attempt-1";

    @Autowired
    private AdminProductFacade adminProductFacade;

    private Long placeOrder(User user, OrderItemCommand... items) {
        return orderFacade.create(user.getId(), List.of(items)).id();
    }

    private CoreException rejected(Runnable action) {
        return assertThrows(CoreException.class, action::run);
    }

    // 거절된 요청은 주문 상태, 포인트, 재고를 바꾸지 않고, PG 호출과 시도 기록도 없어야 한다.
    private void assertUntouched(Long orderId, User user, long balance, Map<Product, Integer> stocks) {
        assertThat(orderStatusOf(orderId)).isEqualTo("DRAFT");
        assertThat(balanceOf(user)).isEqualTo(balance);
        stocks.forEach((product, stock) -> assertThat(stockOf(product)).as(product.getName()).isEqualTo(stock));
        assertThat(pg.createCalls()).isZero();
        assertThat(attemptCount()).isZero();
    }

    @DisplayName("결제를 시작할 때,")
    @Nested
    class Start {
        @DisplayName("여러 품목의 재고를 모두 확보하고 합계를 PG 로 보내며 PAYMENT_PENDING 이 된다. 포인트 잔액은 주문에 저장하지 않는다.")
        @Test
        void securesEveryItem_andSendsTotal() {
            // arrange: 3,000 x 1 + 2,000 x 2 = 7,000
            User user = saveUser(10_000);
            Product shirt = saveProduct("티셔츠", 3_000, 10);
            Product socks = saveProduct("양말", 2_000, 10);
            Long orderId = placeOrder(user, new OrderItemCommand(shirt.getId(), 1), new OrderItemCommand(socks.getId(), 2));

            // act
            PaymentAttemptInfo info = orderFacade.confirm(command(user, orderId, KEY));

            // assert
            assertThat(info.orderStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
            assertThat(info.attemptStatus()).isEqualTo(PaymentAttemptStatus.PENDING);
            assertThat(info.amount()).isEqualTo(7_000L);
            assertThat(stockOf(shirt)).isEqualTo(9);
            assertThat(stockOf(socks)).isEqualTo(8);
            assertThat(pg.transactionsOf(providerOrderIdOf(orderId))).hasSize(1);
            assertThat(pg.transactionsOf(providerOrderIdOf(orderId)).get(0).amount()).isEqualTo(7_000L);
            assertThat(jdbcTemplate.queryForObject("select paid_amount from orders where id = ?", Long.class, orderId))
                .as("결제 결과는 PG 성공을 확인하기 전에는 저장하지 않는다").isNull();
        }

        @DisplayName("재고가 수량과 정확히 같아도 시작되며 재고는 0이 된다.")
        @Test
        void starts_whenStockIsExactlyEnough() {
            // arrange
            User user = saveUser(0);
            Product shirt = saveProduct("티셔츠", 3_000, 2);
            Long orderId = placeOrder(user, new OrderItemCommand(shirt.getId(), 2));

            // act
            PaymentAttemptInfo info = orderFacade.confirm(command(user, orderId, KEY));

            // assert
            assertThat(info.orderStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
            assertThat(stockOf(shirt)).isZero();
        }

        @DisplayName("주문 뒤 관리자가 가격을 바꿔도 PG 로 보내는 금액과 시도의 금액은 주문 당시 합계다.")
        @Test
        void sendsOrderTotal_evenWhenPriceChangesAfterOrder() throws Exception {
            // arrange
            User user = saveUser(0);
            Product shirt = saveProduct("티셔츠", 3_000, 10);
            Long orderId = placeOrder(user, new OrderItemCommand(shirt.getId(), 2));
            adminProductFacade.update(shirt.getId(), "티셔츠", 500_000);

            // act
            PaymentAttemptInfo info = orderFacade.confirm(command(user, orderId, KEY));

            // assert
            assertThat(info.amount()).isEqualTo(6_000L);
            assertThat(pg.lastRequest().json().get("amount").asLong()).isEqualTo(6_000L);
            assertThat(attemptOf(orderId).get("amount")).isEqualTo(6_000L);
        }

        @DisplayName("포인트 잔액이 0이어도 시작되고 잔액은 그대로다. 결제 수단이 포인트가 아니다.")
        @Test
        void starts_regardlessOfPointBalance() {
            // arrange
            User user = saveUser(0);
            Product shirt = saveProduct("티셔츠", 3_000, 10);
            Long orderId = placeOrder(user, new OrderItemCommand(shirt.getId(), 1));

            // act
            PaymentAttemptInfo info = orderFacade.confirm(command(user, orderId, KEY));

            // assert
            assertThat(info.orderStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
            assertThat(balanceOf(user)).isZero();
        }
    }

    @DisplayName("결제를 시작할 수 없을 때,")
    @Nested
    class Rejected {
        @DisplayName("없는 주문과 다른 사용자의 주문은 같은 NOT_FOUND 다. 아무것도 바꾸지 않고 PG 를 호출하지 않는다.")
        @Test
        void throwsNotFound_whenOrderIsMissingOrBelongsToOtherUser() {
            // arrange
            User owner = saveUser(10_000);
            User other = saveUser(10_000);
            Product shirt = saveProduct("티셔츠", 3_000, 10);
            Long orderId = placeOrder(owner, new OrderItemCommand(shirt.getId(), 1));

            // act
            CoreException missing = rejected(() -> orderFacade.confirm(command(owner, 999_999L, KEY)));
            CoreException otherUsers = rejected(() -> orderFacade.confirm(command(other, orderId, KEY)));

            // assert: 다른 사람의 주문이 존재하는지 알 수 없다.
            assertThat(missing.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
            assertThat(otherUsers.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
            assertThat(otherUsers.getMessage()).isEqualTo(missing.getMessage());
            assertUntouched(orderId, owner, 10_000, Map.of(shirt, 10));
            assertThat(balanceOf(other)).isEqualTo(10_000L);
        }

        @DisplayName("이미 확정된 주문은 재고가 0이어도 ORDER_ALREADY_CONFIRMED 가 먼저이고, 재고와 포인트를 건드리지 않는다.")
        @Test
        void throwsAlreadyConfirmed_beforeStockCheck() {
            // arrange: 포인트로 결제해 이미 확정된 과거 주문. 그사이 재고가 0이 되었다.
            User user = saveUser(4_000);
            Product shirt = saveProduct("티셔츠", 3_000, 2);
            Long orderId = placeOrder(user, new OrderItemCommand(shirt.getId(), 2));
            ConfirmedOrders.confirm(jdbcTemplate, orderId);
            jdbcTemplate.update("update products set stock = 0 where id = ?", shirt.getId());

            // act
            CoreException exception = rejected(() -> orderFacade.confirm(command(user, orderId, KEY)));

            // assert
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.ORDER_ALREADY_CONFIRMED);
            assertThat(orderStatusOf(orderId)).isEqualTo("CONFIRMED");
            assertThat(stockOf(shirt)).isZero();
            assertThat(balanceOf(user)).isEqualTo(4_000L);
            assertThat(pg.createCalls()).isZero();
            assertThat(attemptCount()).isZero();
        }

        @DisplayName("삭제된 상품이 있으면 PRODUCT_UNAVAILABLE 이고 메시지에 삭제된 상품의 주문 당시 이름을 모두 담는다. 아무것도 바꾸지 않는다.")
        @Test
        void throwsProductUnavailable_withDeletedProductNames() {
            // arrange
            User user = saveUser(10_000);
            Product shirt = saveProduct("티셔츠", 3_000, 10);
            Product socks = saveProduct("양말", 2_000, 10);
            Product hat = saveProduct("모자", 5_000, 10);
            Long orderId = placeOrder(
                user,
                new OrderItemCommand(shirt.getId(), 1),
                new OrderItemCommand(socks.getId(), 1),
                new OrderItemCommand(hat.getId(), 1)
            );
            adminProductFacade.delete(shirt.getId());
            adminProductFacade.delete(hat.getId());

            // act
            CoreException exception = rejected(() -> orderFacade.confirm(command(user, orderId, KEY)));

            // assert
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.PRODUCT_UNAVAILABLE);
            assertThat(exception.getMessage()).contains("(티셔츠, 모자)").doesNotContain("양말");
            assertUntouched(orderId, user, 10_000, Map.of(shirt, 10, socks, 10, hat, 10));
        }

        @DisplayName("품목 중 뒤쪽 재고가 부족하거나 0이 되었으면 OUT_OF_STOCK 이고, 앞 품목에서 이미 확보한 재고도 원래대로 돌아간다.")
        @ParameterizedTest
        @ValueSource(ints = {0, 1})
        void rollsBackEarlierStock_whenLaterItemIsOutOfStock(int remainingStock) {
            // arrange: 주문을 만들 때는 충분했지만, 결제를 시작하기 전에 관리자가 모자 재고를 줄였다.
            User user = saveUser(10_000);
            Product shirt = saveProduct("티셔츠", 3_000, 10);
            Product hat = saveProduct("모자", 5_000, 5);
            Long orderId = placeOrder(user, new OrderItemCommand(shirt.getId(), 2), new OrderItemCommand(hat.getId(), 2));
            adminProductFacade.changeStock(hat.getId(), remainingStock);

            // act
            CoreException exception = rejected(() -> orderFacade.confirm(command(user, orderId, KEY)));

            // assert
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.OUT_OF_STOCK);
            assertUntouched(orderId, user, 10_000, Map.of(shirt, 10, hat, remainingStock));
        }

        @DisplayName("오류 우선순위: 삭제된 상품이 있으면 재고 부족보다 PRODUCT_UNAVAILABLE 이 먼저다.")
        @Test
        void followsErrorPriority() {
            // arrange: 모자는 재고가 부족하고, 티셔츠는 삭제된다.
            User user = saveUser(10_000);
            Product shirt = saveProduct("티셔츠", 3_000, 10);
            Product hat = saveProduct("모자", 5_000, 5);
            Long orderId = placeOrder(user, new OrderItemCommand(shirt.getId(), 2), new OrderItemCommand(hat.getId(), 2));
            adminProductFacade.changeStock(hat.getId(), 1);

            // act & assert: 재고 부족만 있으면 OUT_OF_STOCK
            assertThat(rejected(() -> orderFacade.confirm(command(user, orderId, KEY))).getErrorType())
                .as("재고 부족").isEqualTo(ErrorType.OUT_OF_STOCK);

            // 삭제된 상품 + 재고 부족 → PRODUCT_UNAVAILABLE
            adminProductFacade.delete(shirt.getId());
            assertThat(rejected(() -> orderFacade.confirm(command(user, orderId, KEY))).getErrorType())
                .as("삭제된 상품 + 재고 부족").isEqualTo(ErrorType.PRODUCT_UNAVAILABLE);
            assertThat(pg.createCalls()).isZero();
        }
    }
}
