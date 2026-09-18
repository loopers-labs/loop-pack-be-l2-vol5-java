package com.loopers.domain.product;

import com.loopers.support.error.DomainError;
import com.loopers.support.error.DomainException;
import com.loopers.domain.common.Money;
import com.loopers.domain.common.Quantity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductTest {

    private static final Long BRAND_ID = 1L;

    private static String repeat(int length) {
        return "가".repeat(length);
    }

    private static Product registered() {
        return Product.register(BRAND_ID, "코트", Price.of(129_000));
    }

    @Nested
    @DisplayName("PRODUCT-001 · 상품은 이름을 가진다. 1~100자, 공백만은 안 된다")
    class Name {
        @DisplayName("브랜드·이름·가격으로 등록한다")
        @Test
        void registers() {
            Product product = registered();

            assertThat(product.getBrandId()).isEqualTo(BRAND_ID);
            assertThat(product.getName()).isEqualTo("코트");
            assertThat(product.getPrice()).isEqualTo(Price.of(129_000));
        }

        @DisplayName("이름이 null 이면 등록할 수 없다")
        @Test
        void rejectsNullName() {
            assertThatThrownBy(() -> Product.register(BRAND_ID, null, Price.of(1_000)))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @DisplayName("이름이 비어 있거나 공백뿐이면 등록할 수 없다")
        @Test
        void rejectsBlankName() {
            assertThatThrownBy(() -> Product.register(BRAND_ID, "   ", Price.of(1_000)))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @DisplayName("이름이 100자를 넘으면 등록할 수 없다")
        @Test
        void rejectsTooLongName() {
            assertThatThrownBy(() -> Product.register(BRAND_ID, repeat(101), Price.of(1_000)))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @DisplayName("이름이 정확히 100자면 등록할 수 있다")
        @Test
        void acceptsBoundaryName() {
            assertThatCode(() -> Product.register(BRAND_ID, repeat(100), Price.of(1_000)))
                .doesNotThrowAnyException();
        }

        @DisplayName("브랜드가 없으면 등록할 수 없다")
        @Test
        void rejectsNullBrandId() {
            assertThatThrownBy(() -> Product.register(null, "코트", Price.of(1_000)))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("PRODUCT-002 · 가격은 1원 이상 10,000,000원 이하의 정수다")
    class PriceRule {
        @DisplayName("0원은 가격이 될 수 없다 — 합계 0원 주문을 만들지 않기 위해서다")
        @Test
        void rejectsZero() {
            assertThatThrownBy(() -> Price.of(0)).isInstanceOf(IllegalArgumentException.class);
        }

        @DisplayName("음수는 가격이 될 수 없다")
        @Test
        void rejectsNegative() {
            assertThatThrownBy(() -> Price.of(-1)).isInstanceOf(IllegalArgumentException.class);
        }

        @DisplayName("상한을 넘으면 가격이 될 수 없다")
        @Test
        void rejectsAboveMax() {
            assertThatThrownBy(() -> Price.of(10_000_001)).isInstanceOf(IllegalArgumentException.class);
        }

        @DisplayName("하한 1원과 상한 10,000,000원은 가격이 될 수 있다")
        @Test
        void acceptsBoundaries() {
            assertThatCode(() -> Price.of(1)).doesNotThrowAnyException();
            assertThatCode(() -> Price.of(10_000_000)).doesNotThrowAnyException();
        }

        @DisplayName("가격 × 수량은 금액이 된다")
        @Test
        void multipliesByQuantity() {
            assertThat(Price.of(1_500).times(Quantity.of(3))).isEqualTo(Money.of(4_500));
        }
    }

    @Nested
    @DisplayName("PRODUCT-004 · 상품의 브랜드는 등록 후 바뀌지 않는다")
    class BrandIsFixed {
        @DisplayName("수정은 이름과 가격만 받는다 — 브랜드를 바꿀 방법 자체가 없다")
        @Test
        void updateKeepsBrand() {
            Product product = registered();

            product.update("트렌치코트", Price.of(150_000));

            assertThat(product.getBrandId()).isEqualTo(BRAND_ID);
            assertThat(product.getName()).isEqualTo("트렌치코트");
            assertThat(product.getPrice()).isEqualTo(Price.of(150_000));
        }

        @DisplayName("수정도 등록과 같은 규칙을 지난다")
        @Test
        void updateGuardsLikeRegister() {
            Product product = registered();

            assertThatThrownBy(() -> product.update(repeat(101), Price.of(1_000)))
                .isInstanceOf(IllegalArgumentException.class);
            assertThat(product.getName()).isEqualTo("코트");
        }
    }

    @Nested
    @DisplayName("PRODUCT-006 · 삭제된 상품은 수정·재고 변경의 대상이 아니다")
    class Delete {
        @DisplayName("등록 직후에는 삭제 상태가 아니다")
        @Test
        void notDeletedAtRegister() {
            assertThat(registered().isDeleted()).isFalse();
        }

        @DisplayName("삭제하면 삭제 상태가 된다")
        @Test
        void deletes() {
            Product product = registered();

            product.delete();

            assertThat(product.isDeleted()).isTrue();
        }

        @DisplayName("이미 삭제된 상품을 다시 삭제해도 상태는 그대로다")
        @Test
        void deleteIsIdempotent() {
            Product product = registered();

            product.delete();
            product.delete();

            assertThat(product.isDeleted()).isTrue();
        }

        @DisplayName("삭제된 상품은 수정할 수 없다")
        @Test
        void rejectsUpdateAfterDelete() {
            Product product = registered();
            product.delete();

            assertThatThrownBy(() -> product.update("트렌치코트", Price.of(150_000)))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("error", DomainError.PRODUCT_ALREADY_DELETED);
        }

        @DisplayName("거절된 수정은 이름·가격을 건드리지 않는다")
        @Test
        void rejectedUpdateChangesNothing() {
            Product product = registered();
            product.delete();

            assertThatThrownBy(() -> product.update("트렌치코트", Price.of(150_000)))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("error", DomainError.PRODUCT_ALREADY_DELETED);
            assertThat(product.getName()).isEqualTo("코트");
            assertThat(product.getPrice()).isEqualTo(Price.of(129_000));
        }

        @DisplayName("삭제 충돌은 코드로 식별되고 스택트레이스를 남기지 않는다")
        @Test
        void carriesCodeWithoutStackTrace() {
            Product product = registered();
            product.delete();

            assertThatThrownBy(() -> product.update("트렌치코트", Price.of(150_000)))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("error", DomainError.PRODUCT_ALREADY_DELETED);
        }
    }
}
