package com.loopers.domain.product;

import com.loopers.domain.common.Money;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductModelTest {

    private static ProductModel product(int stock) {
        return new ProductModel(1L, "에어맥스", 100_000, stock);
    }

    @DisplayName("상품을 만들 때, ")
    @Nested
    class Create {

        @DisplayName("PRD-01 이름 100자, 가격 1원·1억 원, 재고 0개는 허용한다.")
        @Test
        void acceptsBoundaryValues() {
            // act
            ProductModel cheapest = new ProductModel(1L, "가".repeat(100), 1, 0);
            ProductModel priciest = new ProductModel(1L, " 에어맥스 ", 100_000_000, 10);

            // assert
            assertThat(cheapest.getName()).hasSize(100);
            assertThat(cheapest.getPrice()).isEqualTo(Money.of(1));
            assertThat(cheapest.getStock()).isEqualTo(0);
            assertThat(priciest.getName()).isEqualTo("에어맥스");
            assertThat(priciest.getPrice()).isEqualTo(Money.of(100_000_000));
            assertThat(priciest.getBrandId()).isEqualTo(1L);
        }

        @DisplayName("PRD-01 공백뿐인 이름이나 101자 이름은 거절한다.")
        @Test
        void rejectsInvalidName() {
            // act & assert
            assertThatThrownBy(() -> new ProductModel(1L, "  ", 1_000, 0))
                .isInstanceOf(CoreException.class)
                .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST);
            assertThatThrownBy(() -> new ProductModel(1L, "가".repeat(101), 1_000, 0))
                .isInstanceOf(CoreException.class)
                .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST);
        }

        @DisplayName("PRD-01 가격 0원, 100,000,001원, -1원은 거절한다.")
        @Test
        void rejectsPriceOutOfRange() {
            // act & assert
            assertThatThrownBy(() -> new ProductModel(1L, "에어맥스", 0, 0))
                .isInstanceOf(CoreException.class)
                .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST);
            assertThatThrownBy(() -> new ProductModel(1L, "에어맥스", 100_000_001, 0))
                .isInstanceOf(CoreException.class)
                .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST);
            assertThatThrownBy(() -> new ProductModel(1L, "에어맥스", -1, 0))
                .isInstanceOf(CoreException.class)
                .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST);
        }

        @DisplayName("PRD-01 재고 -1개는 거절한다.")
        @Test
        void rejectsNegativeStock() {
            // act & assert
            assertThatThrownBy(() -> new ProductModel(1L, "에어맥스", 1_000, -1))
                .isInstanceOf(CoreException.class)
                .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST);
        }
    }

    @DisplayName("상품을 수정할 때, ")
    @Nested
    class Update {

        @DisplayName("PRD-03 이름·가격만 바꾸고 브랜드는 그대로다.")
        @Test
        void updatesNameAndPriceOnly() {
            // arrange
            ProductModel product = product(5);

            // act
            product.update("에어포스", 2_000);

            // assert
            assertThat(product.getName()).isEqualTo("에어포스");
            assertThat(product.getPrice()).isEqualTo(Money.of(2_000));
            assertThat(product.getBrandId()).isEqualTo(1L);
        }

        @DisplayName("PRD-03 수정할 가격이 유효하지 않으면(0원) 거절하고, 이름·가격은 그대로다.")
        @Test
        void rejectsInvalidUpdateAndKeepsValues() {
            // arrange
            ProductModel product = product(5);

            // act & assert
            assertThatThrownBy(() -> product.update("에어포스", 0))
                .isInstanceOf(CoreException.class)
                .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST);
            assertThat(product.getName()).isEqualTo("에어맥스");
            assertThat(product.getPrice()).isEqualTo(Money.of(100_000));
        }

        @DisplayName("PRD-03 수정할 이름이 유효하지 않으면(빈 이름) 거절하고, 이름·가격은 그대로다.")
        @Test
        void rejectsInvalidNameUpdateAndKeepsValues() {
            // arrange
            ProductModel product = product(5);

            // act & assert
            assertThatThrownBy(() -> product.update("", 2_000))
                .isInstanceOf(CoreException.class)
                .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST);
            assertThat(product.getName()).isEqualTo("에어맥스");
            assertThat(product.getPrice()).isEqualTo(Money.of(100_000));
        }
    }

    @DisplayName("재고를 바꿀 때, ")
    @Nested
    class Stock {

        @DisplayName("PRD-04 관리자는 재고를 0 이상의 최종 수량으로 설정한다.")
        @Test
        void changesStockToFinalQuantity() {
            // arrange
            ProductModel product = product(5);

            // act
            product.changeStock(0);

            // assert
            assertThat(product.getStock()).isEqualTo(0);
        }

        @DisplayName("PRD-04 음수 재고 설정은 거절하고, 재고는 그대로다.")
        @Test
        void rejectsNegativeFinalStock() {
            // arrange
            ProductModel product = product(5);

            // act & assert
            assertThatThrownBy(() -> product.changeStock(-1))
                .isInstanceOf(CoreException.class)
                .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST);
            assertThat(product.getStock()).isEqualTo(5);
        }

        @DisplayName("PRD-05 재고 5개에서 2개를 차감하면 3개, 5개를 차감하면 0개가 남는다.")
        @Test
        void decreasesStock() {
            // arrange
            ProductModel twoOut = product(5);
            ProductModel allOut = product(5);

            // act
            twoOut.decreaseStock(2);
            allOut.decreaseStock(5);

            // assert
            assertThat(twoOut.getStock()).isEqualTo(3);
            assertThat(allOut.getStock()).isEqualTo(0);
        }

        @DisplayName("PRD-05 재고 5개에서 6개 차감은 거절하고, 재고 5개는 그대로다.")
        @Test
        void rejectsDecreasingMoreThanStock() {
            // arrange
            ProductModel product = product(5);

            // act & assert
            assertThatThrownBy(() -> product.decreaseStock(6))
                .isInstanceOf(CoreException.class)
                .extracting("errorType").isEqualTo(ErrorType.CONFLICT);
            assertThat(product.getStock()).isEqualTo(5);
        }

        @DisplayName("PRD-05 0개 이하 차감은 거절하고, 재고는 그대로다.")
        @Test
        void rejectsNonPositiveQuantity() {
            // arrange
            ProductModel product = product(5);

            // act & assert
            assertThatThrownBy(() -> product.decreaseStock(0))
                .isInstanceOf(CoreException.class)
                .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST);
            assertThat(product.getStock()).isEqualTo(5);
        }

        @DisplayName("PRD-05 \"이만큼 있나?\"는 차감과 같은 규칙으로 답한다. 5개면 5개는 있고 6개는 없다.")
        @Test
        void answersHasStockWithSameRule() {
            // arrange
            ProductModel product = product(5);

            // act & assert
            assertThat(product.hasStock(5)).isTrue();
            assertThat(product.hasStock(6)).isFalse();
        }
    }

    @DisplayName("팔 수 있는 상품인지 물을 때, ")
    @Nested
    class Sellable {

        @DisplayName("PRD-06 삭제되지 않은 상품은 팔 수 있고, 삭제된 상품은 팔 수 없다.")
        @Test
        void isNotSellableAfterDelete() {
            // arrange
            ProductModel product = product(0);
            boolean sellableBeforeDelete = product.isSellable();

            // act
            product.delete();

            // assert
            assertThat(sellableBeforeDelete).isTrue();
            assertThat(product.isSellable()).isFalse();
        }
    }
}
