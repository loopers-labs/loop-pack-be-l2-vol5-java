package com.loopers.domain.product;

import com.loopers.support.error.DomainError;
import com.loopers.support.error.DomainException;
import com.loopers.domain.common.Quantity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductStockRuleTest {

    private static Product productWith(int quantity) {
        return Product.restore(10L, 1L, "코트", Price.of(129_000), Quantity.of(quantity), false);
    }

    @Nested
    @DisplayName("PRODUCT-020 · 재고는 0 이상의 정수다")
    class QuantityRule {
        @DisplayName("음수 수량은 존재할 수 없다")
        @Test
        void rejectsNegative() {
            assertThatThrownBy(() -> Quantity.of(-1)).isInstanceOf(IllegalArgumentException.class);
        }

        @DisplayName("0은 수량이 될 수 있다")
        @Test
        void acceptsZero() {
            assertThatCode(() -> Quantity.of(0)).doesNotThrowAnyException();
        }

        @DisplayName("등록 직후 재고는 0이다")
        @Test
        void opensEmpty() {
            assertThat(Product.register(1L, "코트", Price.of(129_000)).getQuantity())
                .isEqualTo(Quantity.ZERO);
        }
    }

    @Nested
    @DisplayName("PRODUCT-021 · 재고 변경은 증감이 아니라 최종 수량을 설정한다")
    class Adjust {
        @DisplayName("설정한 수량이 그대로 재고가 된다")
        @Test
        void setsFinalQuantity() {
            Product product = Product.register(1L, "코트", Price.of(129_000));

            product.adjustTo(Quantity.of(30));
            product.adjustTo(Quantity.of(12));

            assertThat(product.getQuantity()).isEqualTo(Quantity.of(12));
        }

        @DisplayName("0으로 설정할 수 있다 — 품절은 정상 상태다")
        @Test
        void setsZero() {
            Product product = productWith(5);

            product.adjustTo(Quantity.ZERO);

            assertThat(product.getQuantity()).isEqualTo(Quantity.ZERO);
            assertThat(product.isSoldOut()).isTrue();
        }

        @DisplayName("재고를 바꿔도 이름·가격은 그대로다 — 상품 수정과 재고 조정은 다른 일이다")
        @Test
        void doesNotTouchNameOrPrice() {
            Product product = productWith(5);

            product.adjustTo(Quantity.of(99));

            assertThat(product.getName()).isEqualTo("코트");
            assertThat(product.getPrice()).isEqualTo(Price.of(129_000));
        }
    }

    @Nested
    @DisplayName("PRODUCT-023 · 차감으로 재고가 음수가 되지 않는다")
    class Deduct {
        @DisplayName("보유한 만큼은 차감된다")
        @Test
        void deducts() {
            Product product = productWith(5);

            product.deduct(Quantity.of(3));

            assertThat(product.getQuantity()).isEqualTo(Quantity.of(2));
        }

        @DisplayName("정확히 남은 수량만큼 차감하면 0이 된다")
        @Test
        void deductsToZero() {
            Product product = productWith(3);

            product.deduct(Quantity.of(3));

            assertThat(product.getQuantity()).isEqualTo(Quantity.ZERO);
        }

        @DisplayName("남은 수량보다 많이 차감할 수 없다")
        @Test
        void rejectsOverDeduct() {
            Product product = productWith(3);

            assertThatThrownBy(() -> product.deduct(Quantity.of(4)))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("error", DomainError.INSUFFICIENT_STOCK);
        }

        @DisplayName("거절된 차감은 재고를 건드리지 않는다")
        @Test
        void rejectedDeductChangesNothing() {
            Product product = productWith(3);

            assertThatThrownBy(() -> product.deduct(Quantity.of(4)))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("error", DomainError.INSUFFICIENT_STOCK);
            assertThat(product.getQuantity()).isEqualTo(Quantity.of(3));
        }

        @DisplayName("재고 부족은 코드로 식별되고 스택트레이스를 남기지 않는다")
        @Test
        void carriesCodeWithoutStackTrace() {
            Product product = productWith(0);

            assertThatThrownBy(() -> product.deduct(Quantity.of(1)))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("error", DomainError.INSUFFICIENT_STOCK);
        }

        @DisplayName("0을 차감하는 요청은 도달하지 않는다 — 차감량은 양수 타입이 아니라 규칙으로 막는다")
        @Test
        void rejectsZeroDeduct() {
            Product product = productWith(3);

            assertThatThrownBy(() -> product.deduct(Quantity.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("PRODUCT-022 · 재고가 0이어도 상품은 살아 있다")
    class SoldOutIsNotDeleted {
        @DisplayName("품절과 삭제는 다른 질문이다 — 같은 객체가 둘 다 답한다")
        @Test
        void soldOutIsSeparateFromDeleted() {
            Product product = productWith(0);

            assertThat(product.isSoldOut()).isTrue();
            assertThat(product.isDeleted()).isFalse();
        }
    }
}
