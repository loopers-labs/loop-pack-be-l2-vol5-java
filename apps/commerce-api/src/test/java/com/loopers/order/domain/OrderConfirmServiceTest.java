package com.loopers.order.domain;

import com.loopers.product.domain.Product;
import com.loopers.product.domain.Stock;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorCode;
import com.loopers.user.domain.Point;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OrderConfirmServiceTest {
    private static final ZonedDateTime PAID_AT = ZonedDateTime.parse("2026-09-17T12:00:00+09:00");

    @DisplayName("[INV-ORDER-43] 결제액은 확정 시점의 주문 합계다.")
    @Nested
    class PaymentAmount {
        @Test
        void recordsOrderTotal() {
            Scenario s = scenario(5, 4, 10_000L);
            s.confirm(1L);
            assertThat(s.order().getPaymentResult().amount()).isEqualTo(7_000L);
        }
    }

    @Test
    @DisplayName("[INV-ORDER-36] 재고와 포인트가 충분하면 주문을 CONFIRMED로 전환하고 결제 결과를 기록한다.")
    void confirmsOrderAfterValidatingStockAndPoint() {
        Scenario s = scenario(5, 4, 10_000L);
        s.confirm(1L);
        assertAll(
            () -> assertThat(s.order().getStatus()).isEqualTo(OrderStatus.CONFIRMED),
            () -> assertThat(s.order().getPaymentResult().amount()).isEqualTo(7_000L),
            () -> assertThat(s.stockA().quantity()).isEqualTo(5),
            () -> assertThat(s.stockB().quantity()).isEqualTo(4),
            () -> assertThat(s.point().balance()).isEqualTo(10_000L)
        );
    }

    @Test
    @DisplayName("[INV-ORDER-35] 재고 부족이면 주문·재고·포인트를 바꾸지 않는다.")
    void rejectsWhenStockIsInsufficient() {
        Scenario s = scenario(1, 4, 10_000L);
        CoreException result = assertThrows(CoreException.class, () -> s.confirm(1L));
        assertAll(
            () -> assertThat(result.getErrorCode()).isEqualTo(ErrorCode.INSUFFICIENT_STOCK),
            () -> assertThat(s.order().getStatus()).isEqualTo(OrderStatus.DRAFT),
            () -> assertThat(s.stockA().quantity()).isEqualTo(1),
            () -> assertThat(s.stockB().quantity()).isEqualTo(4),
            () -> assertThat(s.point().balance()).isEqualTo(10_000L)
        );
    }

    @Test
    @DisplayName("[INV-ORDER-33] 삭제된 상품은 확정 시 판매 불가로 거절한다.")
    void rejectsDeletedProduct() {
        Scenario s = scenario(5, 4, 10_000L);
        s.productB().delete();
        CoreException result = assertThrows(CoreException.class, () -> s.confirm(1L));
        assertThat(result.getErrorCode()).isEqualTo(ErrorCode.PRODUCT_NOT_AVAILABLE);
    }

    private static Scenario scenario(int stockAQuantity, int stockBQuantity, long balance) {
        Product productA = product(10L, "상품 A", 2_000L);
        Product productB = product(20L, "상품 B", 3_000L);
        return new Scenario(
            new OrderConfirmService(), productA, productB,
            new Stock(productA.getId(), stockAQuantity), new Stock(productB.getId(), stockBQuantity),
            new Point(1L, balance),
            new Order(1L, List.of(OrderItem.of(productA, 2), OrderItem.of(productB, 1)))
        );
    }

    private static Product product(Long id, String name, long price) {
        Product product = new Product(1L, name, price);
        ReflectionTestUtils.setField(product, "id", id);
        return product;
    }

    private record Scenario(OrderConfirmService service, Product productA, Product productB,
                            Stock stockA, Stock stockB, Point point, Order order) {
        void confirm(Long requesterId) {
            service.confirm(requesterId, order, List.of(productA, productB), List.of(stockA, stockB), point, PAID_AT);
        }
    }
}
