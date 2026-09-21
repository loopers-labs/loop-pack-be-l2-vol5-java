package com.loopers.brand.domain;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BrandTest {

    @DisplayName("[INV-01] 브랜드 이름은 앞뒤 공백을 뺀 1자 이상 50자 이하다.")
    @Nested
    class ValidName {

        @DisplayName("[경계값 분석] 이름 길이 1과 50은 허용한다.")
        @Test
        void createsBrand_whenNameLengthIsOnBoundary() {
            // act, assert
            assertAll(
                () -> assertDoesNotThrow(() -> new Brand("a")),
                () -> assertDoesNotThrow(() -> new Brand("a".repeat(50)))
            );
        }

        @DisplayName("[경계값 분석] 이름이 비었거나 51자이면 브랜드 이름 오류로 거절한다.")
        @ParameterizedTest
        @ValueSource(strings = {"", "   ", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})
        void throwsInvalidBrandName_whenNameIsOutsideRange(String name) {
            // act
            CoreException result = assertThrows(CoreException.class, () -> new Brand(name));

            // assert
            assertThat(result.getErrorCode()).isEqualTo(ErrorCode.INVALID_BRAND_NAME);
        }

        @DisplayName("[동등 클래스 분할] 앞뒤 공백을 제거하고 입력한 대소문자는 유지한다.")
        @Test
        void trimsName_andKeepsCase() {
            // act
            Brand brand = new Brand("  Nike  ");

            // assert
            assertThat(brand.getName()).isEqualTo("Nike");
        }

        @DisplayName("[경계값 분석] 앞뒤 공백을 빼면 50자인 이름은 허용한다.")
        @Test
        void createsBrand_whenTrimmedNameFitsInRange() {
            // act, assert
            assertDoesNotThrow(() -> new Brand("  " + "a".repeat(50) + "  "));
        }
    }

    @DisplayName("[INV-04] 삭제된 브랜드의 이름은 바뀌지 않는다.")
    @Nested
    class KeepNameAfterDeletion {

        @DisplayName("[상태 전이] 삭제하면 삭제 여부만 바뀌고 이름은 그대로다.")
        @Test
        void keepsName_whenDeleted() {
            // arrange
            Brand brand = new Brand("브랜드");

            // act
            brand.delete();

            // assert
            assertAll(
                () -> assertThat(brand.isDeleted()).isTrue(),
                () -> assertThat(brand.getName()).isEqualTo("브랜드")
            );
        }

        @DisplayName("[상태 전이] 삭제된 브랜드의 수정을 없는 대상으로 거절하고, 이름과 삭제 상태는 그대로다.")
        @Test
        void throwsBrandNotFound_andKeepsName() {
            // arrange
            Brand brand = new Brand("브랜드");
            brand.delete();

            // act
            CoreException result = assertThrows(
                CoreException.class, () -> brand.update("수정 브랜드"));

            // assert
            assertAll(
                () -> assertThat(result.getErrorCode()).isEqualTo(ErrorCode.BRAND_NOT_FOUND),
                () -> assertThat(brand.getName()).isEqualTo("브랜드"),
                () -> assertThat(brand.isDeleted()).isTrue()
            );
        }
    }

    @DisplayName("[INV-05] 이미 삭제된 브랜드는 다시 삭제되지 않는다.")
    @Nested
    class RejectRepeatedDeletion {

        @DisplayName("[상태 전이] 재삭제를 없는 대상으로 거절하고, 최초 삭제 시점은 그대로다.")
        @Test
        void throwsBrandNotFound_whenAlreadyDeleted() {
            // arrange
            Brand brand = new Brand("브랜드");
            brand.delete();
            var deletedAt = brand.getDeletedAt();

            // act
            CoreException result = assertThrows(CoreException.class, brand::delete);

            // assert
            assertAll(
                () -> assertThat(result.getErrorCode()).isEqualTo(ErrorCode.BRAND_NOT_FOUND),
                () -> assertThat(brand.getDeletedAt()).isEqualTo(deletedAt)
            );
        }
    }
}
