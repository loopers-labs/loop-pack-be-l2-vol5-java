package com.loopers.application.ordering.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.loopers.application.ordering.command.ConfirmOrderCommand;
import com.loopers.application.ordering.dao.ConfirmOrderLoad;
import com.loopers.application.ordering.dao.ConfirmOrderWriter;
import com.loopers.application.ordering.result.ConfirmOrderResult;
import com.loopers.domain.mall.model.Product;
import com.loopers.domain.ordering.model.Order;
import com.loopers.domain.ordering.model.OrderItem;
import com.loopers.domain.ordering.model.OrderStatus;
import com.loopers.domain.pay.model.Wallet;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ConfirmOrderServiceTest {

    @DisplayName("주문 확정")
    @Nested
    class Execute {
        @DisplayName("현재 상품 가격이 바뀌어도 저장된 주문 합계로 결제한다")
        @Test
        void usesStoredTotalAmount_ignoringCurrentProductPrice() {
            // arrange
            ConfirmOrderWriter writer = mock(ConfirmOrderWriter.class);
            Order order = draftOrder(1L, 1L, List.of(OrderItem.restore(10L, "상품", 1_000L, 2, 2_000L)), 2_000L);
            Product product = product(10L, 5_000L, 5);
            Wallet wallet = Wallet.restore(1L, 3_000L);
            given(writer.load(1L)).willReturn(new ConfirmOrderLoad(order, Map.of(10L, product), wallet));
            ConfirmOrderService service = new ConfirmOrderService(writer);

            // act
            ConfirmOrderResult result = service.execute(new ConfirmOrderCommand(1L));

            // assert
            assertThat(result.paymentAmount()).isEqualTo(2_000L);
            assertThat(wallet.getBalance()).isEqualTo(1_000L);
        }
    }

    private Order draftOrder(long id, long userId, List<OrderItem> items, long totalAmount) {
        return Order.restore(id, userId, OrderStatus.DRAFT, items, totalAmount, Instant.now(), null);
    }

    private Product product(long id, long price, int stock) {
        return Product.restore(id, 1L, "상품", null, price, stock, false, Instant.now());
    }
}
