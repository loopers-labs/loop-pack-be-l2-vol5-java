package com.loopers.domain.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class ProductTest {
    @Test
    void 상품_정보_수정_시_브랜드는_유지한다() {
        // arrange
        Product product = Product.create(3L, "상품", 1_000);

        // act
        product.update(" 변경 ", 2_000);

        // assert
        assertThat(product.getBrandId()).isEqualTo(3);
        assertThat(product.getName()).isEqualTo("변경");
        assertThat(product.getPrice()).isEqualTo(2_000);
    }

    @Test
    void 새_상품의_초기_재고는_0개다() {
        // arrange
        long brandId = 3L;

        // act
        Product product = Product.create(brandId, "상품", 1_000);

        // assert
        assertThat(product.getStock()).isZero();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, Integer.MAX_VALUE})
    void 재고_설정은_증감량이_아닌_최종_수량을_저장한다(int stock) {
        // arrange
        Product product = Product.create(3, "상품", 1_000);
        product.setStock(5);

        // act
        product.setStock(stock);

        // assert
        assertThat(product.getStock()).isEqualTo(stock);
    }

    @Test
    void 재고_5개에서_2개를_차감하면_3개가_남는다() {
        // arrange
        Product product = Product.create(3, "상품", 1_000);
        product.setStock(5);

        // act
        product.deductStock(2);

        // assert
        assertThat(product.getStock()).isEqualTo(3);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void 유효하지_않은_차감_수량은_거절하고_재고를_유지한다(int quantity) {
        // arrange
        Product product = Product.create(3, "상품", 1_000);
        product.setStock(5);

        // act
        CoreException error =
                assertThrows(CoreException.class, () -> product.deductStock(quantity));

        // assert
        assertThat(error.getErrorType()).isEqualTo(ErrorType.INVALID_REQUEST);
        assertThat(product.getStock()).isEqualTo(5);
    }

    @Test
    void 음수_재고_설정을_거절하고_기존_수량을_유지한다() {
        // arrange
        Product product = Product.create(3, "상품", 1_000);
        product.setStock(5);

        // act
        CoreException error = assertThrows(CoreException.class, () -> product.setStock(-1));

        // assert
        assertThat(error.getErrorType()).isEqualTo(ErrorType.INVALID_REQUEST);
        assertThat(product.getStock()).isEqualTo(5);
    }

    @Test
    void 잘못된_가격으로_수정하면_이름도_바뀌지_않는다() {
        // arrange
        Product product = Product.create(3L, "상품", 1_000);

        // act
        CoreException error = assertThrows(CoreException.class, () -> product.update("변경", 0));

        // assert
        assertThat(error.getErrorType()).isEqualTo(ErrorType.INVALID_REQUEST);
        assertThat(product.getName()).isEqualTo("상품");
        assertThat(product.getPrice()).isEqualTo(1_000);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" "})
    void 빈_상품_이름으로_등록할_수_없다(String name) {
        // arrange: null, 빈 문자열, 공백을 각각 입력한다

        // act
        CoreException error =
                assertThrows(CoreException.class, () -> Product.create(3, name, 1_000));

        // assert
        assertThat(error.getErrorType()).isEqualTo(ErrorType.INVALID_REQUEST);
    }

    @Test
    void 상품_이름이_100자를_넘으면_등록을_거절한다() {
        // arrange
        String name = "가".repeat(101);

        // act
        CoreException error =
                assertThrows(CoreException.class, () -> Product.create(3, name, 1_000));

        // assert
        assertThat(error.getErrorType()).isEqualTo(ErrorType.INVALID_REQUEST);
    }

    @Test
    void 상품_이름은_100자까지_허용한다() {
        // arrange
        String name = "가".repeat(100);

        // act
        Product product = Product.create(3, name, 1_000);

        // assert
        assertThat(product.getName()).isEqualTo(name);
    }

    @Test
    void 상품_가격은_long_상한까지_허용한다() {
        // arrange
        long price = Long.MAX_VALUE;

        // act
        Product product = Product.create(3, "상품", price);

        // assert
        assertThat(product.getPrice()).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void 삭제된_상품의_삭제_재요청은_성공한다() {
        // arrange
        Product product = Product.create(3, "상품", 1_000);
        product.setStock(5);
        product.delete();

        // act
        product.delete();

        // assert
        assertThat(product.isDeleted()).isTrue();
    }

    @Test
    void 삭제된_상품의_정보는_변경할_수_없다() {
        // arrange
        Product product = Product.create(3, "상품", 1_000);
        product.setStock(5);
        product.delete();

        // act
        CoreException error = assertThrows(CoreException.class, () -> product.update("변경", 2_000));

        // assert
        assertThat(error.getErrorType()).isEqualTo(ErrorType.PRODUCT_NOT_FOUND);
        assertThat(product.getName()).isEqualTo("상품");
        assertThat(product.getPrice()).isEqualTo(1_000);
    }

    @Test
    void 삭제된_상품의_재고는_설정할_수_없다() {
        // arrange
        Product product = Product.create(3, "상품", 1_000);
        product.setStock(5);
        product.delete();

        // act
        CoreException error = assertThrows(CoreException.class, () -> product.setStock(10));

        // assert
        assertThat(error.getErrorType()).isEqualTo(ErrorType.PRODUCT_NOT_FOUND);
        assertThat(product.getStock()).isEqualTo(5);
    }

    @Test
    void 삭제된_상품의_재고는_차감할_수_없다() {
        // arrange
        Product product = Product.create(3, "상품", 1_000);
        product.setStock(5);
        product.delete();

        // act
        CoreException error = assertThrows(CoreException.class, () -> product.deductStock(1));

        // assert
        assertThat(error.getErrorType()).isEqualTo(ErrorType.PRODUCT_NOT_FOUND);
        assertThat(product.getStock()).isEqualTo(5);
    }

    @Test
    void 재고보다_많이_차감하면_재고_부족으로_거절한다() {
        // arrange
        Product product = Product.create(3, "상품", 1_000);
        product.setStock(5);

        // act
        CoreException error = assertThrows(CoreException.class, () -> product.deductStock(6));

        // assert
        assertThat(error.getErrorType()).isEqualTo(ErrorType.INSUFFICIENT_STOCK);
        assertThat(product.getStock()).isEqualTo(5);
    }
}
