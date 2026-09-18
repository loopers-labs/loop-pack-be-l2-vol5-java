package com.loopers.domain.brand;

import com.loopers.support.error.CoreException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BrandTest {

    private static final String NAME_OF_20 = "가".repeat(20);

    @DisplayName("브랜드를 생성할 때, ")
    @Nested
    class Create {
        @DisplayName("이름이 20자 이하이면, 설명 없이도 생성된다.")
        @Test
        void createsBrand_whenNameIsWithinLimit() {
            // act
            Brand brand = new Brand(NAME_OF_20, null);

            // assert
            assertAll(
                () -> assertThat(brand.getName()).isEqualTo(NAME_OF_20),
                () -> assertThat(brand.getDescription()).isNull(),
                () -> assertThat(brand.isDeleted()).isFalse()
            );
        }

        @DisplayName("앞뒤 공백을 자르지 않고 길이에 포함한다.")
        @Test
        void keepsSurroundingSpaces() {
            // act
            Brand brand = new Brand(" 브랜드 ", null);

            // assert
            assertThat(brand.getName()).isEqualTo(" 브랜드 ");
        }

        @DisplayName("이름이 비었거나 공백뿐이면, INVALID_BRAND_NAME 예외가 발생한다.")
        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   "})
        void throwsInvalidBrandName_whenNameIsBlank(String name) {
            // act
            CoreException result = assertThrows(CoreException.class, () -> new Brand(name, null));

            // assert
            assertThat(result.getErrorCode()).isEqualTo(BrandErrorCode.INVALID_BRAND_NAME);
        }

        @DisplayName("설명이 255자 이하이면, 생성된다.")
        @Test
        void createsBrand_whenDescriptionIsWithinLimit() {
            // act
            Brand brand = new Brand("브랜드", "가".repeat(255));

            // assert
            assertThat(brand.getDescription()).hasSize(255);
        }

        @DisplayName("설명이 255자를 넘으면, INVALID_BRAND_DESCRIPTION 예외가 발생한다.")
        @Test
        void throwsInvalidBrandDescription_whenDescriptionExceedsLimit() {
            // act
            CoreException result = assertThrows(CoreException.class, () -> new Brand("브랜드", "가".repeat(256)));

            // assert
            assertThat(result.getErrorCode()).isEqualTo(BrandErrorCode.INVALID_BRAND_DESCRIPTION);
        }

        @DisplayName("이름이 20자를 넘으면, INVALID_BRAND_NAME 예외가 발생한다.")
        @Test
        void throwsInvalidBrandName_whenNameExceedsLimit() {
            // act
            CoreException result = assertThrows(CoreException.class, () -> new Brand(NAME_OF_20 + "가", null));

            // assert
            assertThat(result.getErrorCode()).isEqualTo(BrandErrorCode.INVALID_BRAND_NAME);
        }
    }

    @DisplayName("브랜드를 수정할 때, ")
    @Nested
    class Update {
        @DisplayName("유효한 이름이면, 이름과 설명이 바뀐다.")
        @Test
        void updatesBrand_whenNameIsValid() {
            // arrange
            Brand brand = new Brand("브랜드", "설명");

            // act
            brand.update("새 브랜드", "새 설명");

            // assert
            assertAll(
                () -> assertThat(brand.getName()).isEqualTo("새 브랜드"),
                () -> assertThat(brand.getDescription()).isEqualTo("새 설명")
            );
        }

        @DisplayName("이름이 유효하지 않으면, INVALID_BRAND_NAME 예외가 발생하고 기존 값이 유지된다.")
        @Test
        void throwsInvalidBrandName_andKeepsValues_whenNameIsInvalid() {
            // arrange
            Brand brand = new Brand("브랜드", "설명");

            // act
            CoreException result = assertThrows(CoreException.class, () -> brand.update("   ", "새 설명"));

            // assert
            assertAll(
                () -> assertThat(result.getErrorCode()).isEqualTo(BrandErrorCode.INVALID_BRAND_NAME),
                () -> assertThat(brand.getName()).isEqualTo("브랜드"),
                () -> assertThat(brand.getDescription()).isEqualTo("설명")
            );
        }

        @DisplayName("삭제된 브랜드면, BRAND_NOT_FOUND 예외가 발생하고 기존 값이 유지된다. (BRD-01)")
        @Test
        void throwsBrandNotFound_andKeepsValues_whenBrandIsDeleted() {
            // arrange
            Brand brand = new Brand("브랜드", "설명");
            brand.delete();

            // act
            CoreException result = assertThrows(CoreException.class, () -> brand.update("새 브랜드", "새 설명"));

            // assert
            assertAll(
                () -> assertThat(result.getErrorCode()).isEqualTo(BrandErrorCode.BRAND_NOT_FOUND),
                () -> assertThat(brand.getName()).isEqualTo("브랜드")
            );
        }
    }
}
