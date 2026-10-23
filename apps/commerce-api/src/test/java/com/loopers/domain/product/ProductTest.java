package com.loopers.domain.product;

import com.loopers.domain.common.Money;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProductTest {

    private static final String NAME_100 = "가".repeat(100);
    private static final long MAX_PRICE = 1_000_000_000L;

    // 애노테이션 값에는 repeat() 결과를 쓸 수 없어(컴파일 타임 상수가 아님) 메소드로 제공한다.
    static Stream<String> validNames() {
        return Stream.of("a", NAME_100);
    }

    private Product product() {
        return Product.create(1L, "러닝화", new Money(89_000), new Stock(10));
    }

    private void assertInvalidInput(Runnable action) {
        CoreException exception = assertThrows(CoreException.class, action::run);
        assertThat(exception.getErrorType()).isEqualTo(ErrorType.INVALID_INPUT);
    }

    @DisplayName("상품을 생성할 때,")
    @Nested
    class Create {
        @DisplayName("브랜드·이름·가격·재고를 가지고, 이름의 앞뒤 공백은 제거한다.")
        @Test
        void keepsValues_andTrimsName() {
            // act
            Product product = Product.create(3L, "  러닝화  ", new Money(89_000), new Stock(10));

            // assert
            assertThat(product.getBrandId()).isEqualTo(3L);
            assertThat(product.getName()).isEqualTo("러닝화");
            assertThat(product.getPrice()).isEqualTo(new Money(89_000));
            assertThat(product.getStock()).isEqualTo(new Stock(10));
        }

        @DisplayName("이름이 1자 이상 100자 이하이면 생성된다.")
        @ParameterizedTest
        @MethodSource("com.loopers.domain.product.ProductTest#validNames")
        void createsProduct_whenNameLengthIsWithinRange(String name) {
            // act
            Product product = Product.create(1L, name, new Money(1_000), new Stock(0));

            // assert
            assertThat(product.getName()).isEqualTo(name);
        }

        @DisplayName("이름이 없거나 공백뿐이면 INVALID_INPUT 으로 거절한다.")
        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {" ", "   "})
        void rejectsWithInvalidInput_whenNameIsBlank(String name) {
            assertInvalidInput(() -> Product.create(1L, name, new Money(1_000), new Stock(1)));
        }

        @DisplayName("공백을 제거한 이름이 100자를 넘으면 INVALID_INPUT 으로 거절한다.")
        @Test
        void rejectsWithInvalidInput_whenNameIsLongerThan100() {
            assertInvalidInput(() -> Product.create(1L, NAME_100 + "나", new Money(1_000), new Stock(1)));
        }

        @DisplayName("가격이 1원 이상 1,000,000,000원 이하이면 생성된다.")
        @ParameterizedTest
        @ValueSource(longs = {1, MAX_PRICE})
        void createsProduct_whenPriceIsWithinRange(long price) {
            // act
            Product product = Product.create(1L, "러닝화", new Money(price), new Stock(1));

            // assert
            assertThat(product.getPrice()).isEqualTo(new Money(price));
        }

        @DisplayName("가격이 0원이거나 1,000,000,000원을 넘으면 INVALID_INPUT 으로 거절한다.")
        @ParameterizedTest
        @ValueSource(longs = {0, MAX_PRICE + 1})
        void rejectsWithInvalidInput_whenPriceIsOutOfRange(long price) {
            assertInvalidInput(() -> Product.create(1L, "러닝화", new Money(price), new Stock(1)));
        }

        @DisplayName("브랜드·가격·재고가 없으면 INVALID_INPUT 으로 거절한다.")
        @Test
        void rejectsWithInvalidInput_whenRequiredValueIsNull() {
            assertInvalidInput(() -> Product.create(null, "러닝화", new Money(1_000), new Stock(1)));
            assertInvalidInput(() -> Product.create(1L, "러닝화", null, new Stock(1)));
            assertInvalidInput(() -> Product.create(1L, "러닝화", new Money(1_000), null));
        }
    }

    @DisplayName("상품을 수정할 때,")
    @Nested
    class Update {
        @DisplayName("이름과 가격을 바꾸고, 브랜드와 재고는 그대로다.")
        @Test
        void changesNameAndPrice_only() {
            // arrange
            Product product = product();

            // act
            product.update("  새 러닝화 ", new Money(99_000));

            // assert
            assertThat(product.getName()).isEqualTo("새 러닝화");
            assertThat(product.getPrice()).isEqualTo(new Money(99_000));
            assertThat(product.getBrandId()).isEqualTo(1L);
            assertThat(product.getStock()).isEqualTo(new Stock(10));
        }

        @DisplayName("이름이나 가격이 유효하지 않으면 INVALID_INPUT 으로 거절하고, 기존 값을 유지한다.")
        @Test
        void rejectsWithInvalidInput_andKeepsValues() {
            // arrange
            Product product = product();

            // act & assert
            assertInvalidInput(() -> product.update("   ", new Money(99_000)));
            assertInvalidInput(() -> product.update("새 러닝화", new Money(0)));
            assertInvalidInput(() -> product.update("새 러닝화", new Money(MAX_PRICE + 1)));
            assertInvalidInput(() -> product.update("새 러닝화", null));
            assertThat(product.getName()).isEqualTo("러닝화");
            assertThat(product.getPrice()).isEqualTo(new Money(89_000));
        }
    }

    @DisplayName("재고를 변경할 때,")
    @Nested
    class ChangeStock {
        @DisplayName("최종 수량으로 바꾼다. 0도 허용한다.")
        @ParameterizedTest
        @ValueSource(ints = {0, 25})
        void changesStock(int quantity) {
            // arrange
            Product product = product();

            // act
            product.changeStock(quantity);

            // assert
            assertThat(product.getStock()).isEqualTo(new Stock(quantity));
        }

        @DisplayName("음수이면 INVALID_INPUT 으로 거절하고, 기존 재고를 유지한다.")
        @Test
        void rejectsWithInvalidInput_andKeepsStock_whenQuantityIsNegative() {
            // arrange
            Product product = product();

            // act & assert
            assertInvalidInput(() -> product.changeStock(-1));
            assertThat(product.getStock()).isEqualTo(new Stock(10));
        }
    }

    @DisplayName("재고를 차감할 때,")
    @Nested
    class DecreaseStock {
        @DisplayName("보유량 이하이면 그만큼 줄어든다 (경계값은 StockTest).")
        @Test
        void decreasesStock() {
            // arrange
            Product product = product();

            // act
            product.decreaseStock(2);

            // assert
            assertThat(product.getStock()).isEqualTo(new Stock(8));
        }

        @DisplayName("보유량을 넘으면 OUT_OF_STOCK 으로 거절하고, 기존 재고를 유지한다.")
        @Test
        void rejectsWithOutOfStock_andKeepsStock_whenQuantityExceedsStock() {
            // arrange
            Product product = product();

            // act
            CoreException exception = assertThrows(CoreException.class, () -> product.decreaseStock(11));

            // assert
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.OUT_OF_STOCK);
            assertThat(product.getStock()).isEqualTo(new Stock(10));
        }

    }

    @DisplayName("주문 수량에 재고가 충분한지 확인할 때,")
    @Nested
    class HasEnoughStock {
        @DisplayName("재고가 수량 이상이면 true, 적으면 false 이고, 재고는 바뀌지 않는다.")
        @ParameterizedTest
        @CsvSource({"10, true", "11, false"})
        void judgesBySufficiency_withoutChangingStock(int quantity, boolean expected) {
            // arrange
            Product product = product();

            // act & assert
            assertThat(product.hasEnoughStock(quantity)).isEqualTo(expected);
            assertThat(product.getStock()).isEqualTo(new Stock(10));
        }

    }

    @DisplayName("재고를 돌려줄 때,")
    @Nested
    class IncreaseStock {
        @DisplayName("수량만큼 재고가 늘어난다.")
        @Test
        void increasesStock() {
            // arrange
            Product product = product();

            // act
            product.increaseStock(2);

            // assert
            assertThat(product.getStock()).isEqualTo(new Stock(12));
        }

        @DisplayName("0 이하의 수량이면 INVALID_INPUT 으로 거절하고, 재고는 그대로다.")
        @ParameterizedTest
        @ValueSource(ints = {0, -1})
        void rejectsWithInvalidInput_andKeepsStock_whenQuantityIsNotPositive(int quantity) {
            // arrange
            Product product = product();

            // act & assert
            assertInvalidInput(() -> product.increaseStock(quantity));
            assertThat(product.getStock()).isEqualTo(new Stock(10));
        }

        @DisplayName("삭제된 상품도 수량을 돌려받고, 삭제 상태는 그대로다 (실패 반환이 상품을 다시 노출하지 않는다).")
        @Test
        void increasesStock_andKeepsDeleted_whenProductIsDeleted() {
            // arrange
            Product product = product();
            product.delete();

            // act
            product.increaseStock(2);

            // assert
            assertThat(product.getStock()).isEqualTo(new Stock(12));
            assertThat(product.getDeletedAt()).isNotNull();
        }
    }
}
