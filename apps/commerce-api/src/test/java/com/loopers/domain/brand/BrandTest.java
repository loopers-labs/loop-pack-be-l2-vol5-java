package com.loopers.domain.brand;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BrandTest {
    @Test
    @DisplayName("미삭제 상품이 있으면 브랜드 삭제를 거절한다")
    void rejectsDeletionWithActiveProducts() {
        // arrange
        Brand brand = Brand.create("브랜드");

        // act
        CoreException error = assertThrows(CoreException.class, () -> brand.delete(true));

        // assert
        assertThat(error.getErrorType()).isEqualTo(ErrorType.BRAND_HAS_ACTIVE_PRODUCTS);
        assertThat(brand.isDeleted()).isFalse();
    }

    @Test
    @DisplayName("미삭제 상품이 없으면 브랜드를 소프트 삭제한다")
    void deletesBrand() {
        // arrange
        Brand brand = Brand.create("브랜드");

        // act
        brand.delete(false);

        // assert
        assertThat(brand.isDeleted()).isTrue();
    }

    @Test
    @DisplayName("이미 삭제한 브랜드를 다시 삭제해도 성공한다")
    void repeatsDeletion() {
        // arrange
        Brand brand = Brand.create("브랜드");
        brand.delete(false);

        // act
        brand.delete(false);

        // assert
        assertThat(brand.isDeleted()).isTrue();
    }

    @Test
    @DisplayName("삭제된 브랜드는 이름을 바꿀 수 없다")
    void rejectsRenameAfterDeletion() {
        // arrange
        Brand brand = Brand.create("브랜드");
        brand.delete(false);

        // act
        CoreException error = assertThrows(CoreException.class, () -> brand.rename("변경"));

        // assert
        assertThat(error.getErrorType()).isEqualTo(ErrorType.BRAND_NOT_FOUND);
        assertThat(brand.getName()).isEqualTo("브랜드");
    }

    @Test
    @DisplayName("등록할 때 이름의 앞뒤 공백을 제거한다")
    void trimsName() {
        // arrange
        String name = "  브랜드  ";

        // act
        Brand brand = Brand.create(name);

        // assert
        assertThat(brand.getName()).isEqualTo("브랜드");
    }

    @Test
    @DisplayName("50자 이름으로 수정할 수 있다")
    void allowsBoundaryLength() {
        // arrange
        Brand brand = Brand.create("브랜드");
        String name = "가".repeat(50);

        // act
        brand.rename(name);

        // assert
        assertThat(brand.getName()).isEqualTo(name);
    }

    @Test
    @DisplayName("50자를 넘는 수정은 거절하고 기존 이름을 유지한다")
    void rejectsLongNameWithoutChangingName() {
        // arrange
        Brand brand = Brand.create("가".repeat(50));

        // act
        CoreException error = assertThrows(CoreException.class, () -> brand.rename("가".repeat(51)));

        // assert
        assertThat(error.getErrorType()).isEqualTo(ErrorType.INVALID_REQUEST);
        assertThat(brand.getName()).isEqualTo("가".repeat(50));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "   "})
    @DisplayName("빈 이름으로 브랜드를 등록할 수 없다")
    void rejectsBlankName(String name) {
        // arrange: null, 빈 문자열, 공백을 각각 입력한다

        // act
        CoreException error = assertThrows(CoreException.class, () -> Brand.create(name));

        // assert
        assertThat(error.getErrorType()).isEqualTo(ErrorType.INVALID_REQUEST);
    }
}
