package com.loopers.domain.brand;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class BrandTest {
    @Test
    void 브랜드_삭제는_이름을_보존하고_삭제_상태만_변경한다() {
        // arrange
        Brand brand = Brand.create("브랜드");

        // act
        brand.delete();

        // assert
        assertThat(brand.getName()).isEqualTo("브랜드");
        assertThat(brand.isDeleted()).isTrue();
    }

    @Test
    void 미삭제_상품이_없으면_브랜드를_소프트_삭제한다() {
        // arrange
        Brand brand = Brand.create("브랜드");

        // act
        brand.delete();

        // assert
        assertThat(brand.isDeleted()).isTrue();
    }

    @Test
    void 이미_삭제한_브랜드를_다시_삭제해도_성공한다() {
        // arrange
        Brand brand = Brand.create("브랜드");
        brand.delete();

        // act
        brand.delete();

        // assert
        assertThat(brand.isDeleted()).isTrue();
    }

    @Test
    void 삭제된_브랜드는_이름을_바꿀_수_없다() {
        // arrange
        Brand brand = Brand.create("브랜드");
        brand.delete();

        // act
        CoreException error = assertThrows(CoreException.class, () -> brand.rename("변경"));

        // assert
        assertThat(error.getErrorType()).isEqualTo(ErrorType.BRAND_NOT_FOUND);
        assertThat(brand.getName()).isEqualTo("브랜드");
    }

    @Test
    void 등록할_때_이름의_앞뒤_공백을_제거한다() {
        // arrange
        String name = "  브랜드  ";

        // act
        Brand brand = Brand.create(name);

        // assert
        assertThat(brand.getName()).isEqualTo("브랜드");
    }

    @Test
    void 이름을_50자로_수정할_수_있다() {
        // arrange
        Brand brand = Brand.create("브랜드");
        String name = "가".repeat(50);

        // act
        brand.rename(name);

        // assert
        assertThat(brand.getName()).isEqualTo(name);
    }

    @Test
    void 이름이_50자를_넘는_수정은_거절하고_기존_이름을_유지한다() {
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
    void 빈_이름으로_브랜드를_등록할_수_없다(String name) {
        // arrange: null, 빈 문자열, 공백을 각각 입력한다

        // act
        CoreException error = assertThrows(CoreException.class, () -> Brand.create(name));

        // assert
        assertThat(error.getErrorType()).isEqualTo(ErrorType.INVALID_REQUEST);
    }
}
