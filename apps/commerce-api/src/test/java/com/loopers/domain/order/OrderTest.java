package com.loopers.domain.order;

import com.loopers.support.error.DomainError;
import com.loopers.support.error.DomainException;
import com.loopers.domain.common.Money;
import com.loopers.domain.product.Price;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderTest {

    private static final Long USER = 1L;
    private static final Instant NOW = Instant.parse("2026-09-14T00:00:00Z");

    private static OrderItem item(Long productId, String name, long price, int quantity) {
        return OrderItem.of(productId, name, Price.of(price), OrderQuantity.of(quantity));
    }

    private static Order draft(OrderItem... items) {
        return Order.draft(USER, List.of(items), NOW);
    }

    @Nested
    @DisplayName("ORDER-001 · 002 · 주문은 품목을 1개 이상 가지고, 수량은 1 이상이다")
    class Items {
        @DisplayName("품목이 없으면 접수할 수 없다")
        @Test
        void rejectsEmptyItems() {
            assertThatThrownBy(() -> Order.draft(USER, List.of(), NOW))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @DisplayName("수량이 0이면 품목이 될 수 없다 — 타입이 막는다. 사는 게 없는 줄은 표현 불가능하다")
        @Test
        void rejectsZeroQuantity() {
            assertThatThrownBy(() -> OrderQuantity.of(0)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> OrderQuantity.of(-1)).isInstanceOf(IllegalArgumentException.class);
        }

        @DisplayName("주문자가 없으면 접수할 수 없다")
        @Test
        void rejectsNullUser() {
            assertThatThrownBy(() -> Order.draft(null, List.of(item(10L, "코트", 1_000, 1)), NOW))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("ORDER-004 · 같은 상품이 여러 품목으로 오면 수량을 합산한다")
    class Merge {
        @DisplayName("같은 상품 둘은 한 품목 수량 5가 된다")
        @Test
        void mergesSameProduct() {
            Order order = draft(item(10L, "코트", 1_000, 2), item(10L, "코트", 1_000, 3));

            assertThat(order.getItems()).hasSize(1);
            assertThat(order.getItems().get(0).quantity()).isEqualTo(OrderQuantity.of(5));
        }

        @DisplayName("합산해도 합계는 같다 — 합산은 표현을 줄일 뿐 금액을 바꾸지 않는다")
        @Test
        void keepsTotalAfterMerge() {
            Order merged = draft(item(10L, "코트", 1_000, 2), item(10L, "코트", 1_000, 3));
            Order single = draft(item(10L, "코트", 1_000, 5));

            assertThat(merged.getTotalAmount()).isEqualTo(single.getTotalAmount());
        }

        @DisplayName("다른 상품은 합쳐지지 않는다")
        @Test
        void keepsDifferentProductsApart() {
            Order order = draft(item(10L, "코트", 1_000, 2), item(20L, "셔츠", 500, 1));

            assertThat(order.getItems()).hasSize(2);
        }

        @DisplayName("첫 등장 순서를 지킨다 — 같은 요청은 같은 주문을 만든다")
        @Test
        void keepsFirstAppearanceOrder() {
            Order order = draft(
                item(20L, "셔츠", 500, 1), item(10L, "코트", 1_000, 2), item(20L, "셔츠", 500, 3));

            assertThat(order.getItems()).extracting(OrderItem::productId).containsExactly(20L, 10L);
        }
    }

    @Nested
    @DisplayName("ORDER-005 · 006 · 단가는 접수 시점의 복사본이고, 합계는 접수 시 계산해 저장한다")
    class Total {
        @DisplayName("합계는 단가 × 수량의 합이다")
        @Test
        void sumsLineTotals() {
            Order order = draft(item(10L, "코트", 1_000, 2), item(20L, "셔츠", 500, 3));

            assertThat(order.getTotalAmount()).isEqualTo(Money.of(3_500));
        }

        @DisplayName("품목은 상품명도 복사해 든다 — 나중에 이름이 바뀌어도 주문은 그대로다 (ORDER-022)")
        @Test
        void copiesProductName() {
            Order order = draft(item(10L, "코트", 1_000, 1));

            assertThat(order.getItems().get(0).productName()).isEqualTo("코트");
        }

        @DisplayName("접수 직후 상태는 DRAFT 이고 결제액은 없다 (ORDER-007)")
        @Test
        void startsAsDraft() {
            Order order = draft(item(10L, "코트", 1_000, 1));

            assertThat(order.getStatus()).isEqualTo(OrderStatus.DRAFT);
            assertThat(order.getPaidAmount()).isNull();
        }
    }

    @Nested
    @DisplayName("ORDER-010 · 016 · 017 · 확정은 DRAFT 에만, 한 번만")
    class Confirm {
        @DisplayName("확정하면 CONFIRMED 가 되고 결제액은 합계와 같다")
        @Test
        void confirms() {
            Order order = draft(item(10L, "코트", 1_000, 2));

            order.confirm(NOW);

            assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(order.getPaidAmount()).isEqualTo(Money.of(2_000));
            assertThat(order.getConfirmedAt()).isEqualTo(NOW);
        }

        @DisplayName("이미 확정된 주문은 다시 확정할 수 없다 — 멱등이 아니라 거절이다")
        @Test
        void rejectsSecondConfirm() {
            Order order = draft(item(10L, "코트", 1_000, 1));
            order.confirm(NOW);

            assertThatThrownBy(() -> order.confirm(NOW))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("error", DomainError.ORDER_NOT_DRAFT);
        }

        @DisplayName("거절된 확정은 결제액을 건드리지 않는다")
        @Test
        void rejectedConfirmChangesNothing() {
            Order order = draft(item(10L, "코트", 1_000, 1));
            order.confirm(NOW);
            Instant later = NOW.plusSeconds(60);

            assertThatThrownBy(() -> order.confirm(later)).isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("error", DomainError.ORDER_NOT_DRAFT);

            assertThat(order.getConfirmedAt()).isEqualTo(NOW);
            assertThat(order.getPaidAmount()).isEqualTo(Money.of(1_000));
        }
    }

    @Nested
    @DisplayName("ORDER-011 · 자기 주문만 다룰 수 있다")
    class Ownership {
        @DisplayName("주문은 주인이 누구인지 답한다 — 주인을 내주지 않는다")
        @Test
        void answersOwnership() {
            Order order = draft(item(10L, "코트", 1_000, 1));

            assertThat(order.isOwnedBy(USER)).isTrue();
            assertThat(order.isOwnedBy(2L)).isFalse();
        }
    }
}
