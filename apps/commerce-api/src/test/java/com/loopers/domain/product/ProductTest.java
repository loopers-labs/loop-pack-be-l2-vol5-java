package com.loopers.domain.product;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProductTest {
    @Test
    @DisplayName("상품 정보 수정 시 브랜드는 유지한다")
    void preservesBrandOnUpdate() {
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
    @DisplayName("새 상품의 초기 재고는 0개다")
    void createsWithZeroStock() {
        // arrange
        long brandId = 3L;

        // act
        Product product = Product.create(brandId, "상품", 1_000);

        // assert
        assertThat(product.getStock()).isZero();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, Integer.MAX_VALUE})
    @DisplayName("재고 설정은 증감량이 아닌 최종 수량을 저장한다")
    void setsFinalStock(int stock) {
        // arrange
        Product product = Product.create(3, "상품", 1_000);
        product.setStock(5);

        // act
        product.setStock(stock);

        // assert
        assertThat(product.getStock()).isEqualTo(stock);
    }

    @Test
    @DisplayName("재고 5개에서 2개를 차감하면 3개가 남는다")
    void deductsStock() {
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
    @DisplayName("유효하지 않은 차감 수량은 거절하고 재고를 유지한다")
    void rejectsInvalidDeductionWithoutChangingStock(int quantity) {
        // arrange
        Product product = Product.create(3, "상품", 1_000);
        product.setStock(5);

        // act
        CoreException error = assertThrows(CoreException.class, () -> product.deductStock(quantity));

        // assert
        assertThat(error.getErrorType()).isEqualTo(ErrorType.INVALID_REQUEST);
        assertThat(product.getStock()).isEqualTo(5);
    }

    @Test
    @DisplayName("음수 재고 설정을 거절하고 기존 수량을 유지한다")
    void rejectsNegativeStock() {
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
    @DisplayName("잘못된 가격으로 수정하면 이름도 바뀌지 않는다")
    void rejectsInvalidInformationWithoutPartialChange() {
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
    @DisplayName("빈 상품 이름으로 등록할 수 없다")
    void rejectsBlankName(String name) {
        // arrange: null, 빈 문자열, 공백을 각각 입력한다

        // act
        CoreException error = assertThrows(CoreException.class, () -> Product.create(3, name, 1_000));

        // assert
        assertThat(error.getErrorType()).isEqualTo(ErrorType.INVALID_REQUEST);
    }

    @Test
    @DisplayName("상품 이름이 100자를 넘으면 등록을 거절한다")
    void rejectsLongName() {
        // arrange
        String name = "가".repeat(101);

        // act
        CoreException error = assertThrows(CoreException.class, () -> Product.create(3, name, 1_000));

        // assert
        assertThat(error.getErrorType()).isEqualTo(ErrorType.INVALID_REQUEST);
    }

    @Test
    @DisplayName("상품 이름은 100자까지 허용한다")
    void allowsMaximumNameLength() {
        // arrange
        String name = "가".repeat(100);

        // act
        Product product = Product.create(3, name, 1_000);

        // assert
        assertThat(product.getName()).isEqualTo(name);
    }

    @Test
    @DisplayName("상품 가격은 long 상한까지 허용한다")
    void allowsMaximumPrice() {
        // arrange
        long price = Long.MAX_VALUE;

        // act
        Product product = Product.create(3, "상품", price);

        // assert
        assertThat(product.getPrice()).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    @DisplayName("삭제된 상품의 삭제 재요청은 성공한다")
    void repeatsDeletion() {
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
    @DisplayName("삭제된 상품의 정보는 변경할 수 없다")
    void rejectsUpdateAfterDeletion() {
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
    @DisplayName("삭제된 상품의 재고는 설정할 수 없다")
    void rejectsStockSettingAfterDeletion() {
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
    @DisplayName("삭제된 상품의 재고는 차감할 수 없다")
    void rejectsDeductionAfterDeletion() {
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
    @DisplayName("재고보다 많이 차감하면 재고 부족으로 거절한다")
    void rejectsInsufficientStock() {
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
