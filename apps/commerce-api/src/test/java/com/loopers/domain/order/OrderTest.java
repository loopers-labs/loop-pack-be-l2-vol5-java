package com.loopers.domain.order;

import com.loopers.domain.common.Money;
import com.loopers.domain.product.ProductId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderTest {
    private OrderItem item(long id, int quantity, long price) {
        return new OrderItem(new ProductId(id), new Quantity(quantity), new Money(price));
    }
    @Test
    @DisplayName("여러 품목 금액을 합산한 DRAFT 주문을 확정하면 결제액과 성공 결과를 보존한다")
    void confirmsAndRejectsRepeatedConfirmation() {
        Order order = Order.create(1, List.of(item(1, 2, 2000), item(2, 1, 3000)));
        assertThat(order.getTotal()).isEqualTo(new Money(7000));
        assertThat(order.getStatus()).isEqualTo(Order.Status.DRAFT);
        order.confirm();
        assertThat(order.getStatus()).isEqualTo(Order.Status.CONFIRMED);
        assertThat(order.getPaidAmount()).isEqualTo(new Money(7000));
        assertThat(order.getPaymentResult()).isEqualTo("SUCCESS");
        assertThatThrownBy(order::confirm).isInstanceOf(IllegalStateException.class);
    }
    @Test
    @DisplayName("빈 주문과 중복 상품 품목은 거절한다")
    void rejectsEmptyAndDuplicateItems() {
        assertThatThrownBy(() -> Order.create(1, List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Order.create(1, List.of(item(1, 1, 100), item(1, 2, 100))))
            .isInstanceOf(IllegalArgumentException.class);
    }
    @Test
    @DisplayName("주문 품목은 외부에서 변경할 수 없고 0원 주문도 확정할 수 있다")
    void protectsItemsAndAllowsZeroTotal() {
        Order order = Order.create(1, List.of(item(1, 1, 0)));
        assertThatThrownBy(() -> order.getItems().clear()).isInstanceOf(UnsupportedOperationException.class);
        order.confirm();
        assertThat(order.getPaidAmount()).isEqualTo(new Money(0));
    }
}
