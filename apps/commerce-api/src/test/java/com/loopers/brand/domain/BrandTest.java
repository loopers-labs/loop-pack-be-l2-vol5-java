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

    @DisplayName("[R-ADMIN-15] 관리자가 저장하는 브랜드 정보는 유효해야 한다.")
    @Nested
    class ValidName {

        @DisplayName("[경계값 분석] 이름 길이 1과 50은 허용한다.")
        @Test
        void createsBrand_whenNameLengthIsOnBoundary() {
            assertAll(
                () -> assertDoesNotThrow(() -> new Brand("a")),
                () -> assertDoesNotThrow(() -> new Brand("a".repeat(50)))
            );
        }

        @DisplayName("[경계값 분석] 이름이 비었거나 51자이면 브랜드 이름 오류로 거절한다.")
        @ParameterizedTest
        @ValueSource(strings = {"", "   ", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})
        void throwsInvalidBrandName_whenNameIsOutsideRange(String name) {
            CoreException result = assertThrows(CoreException.class, () -> new Brand(name));

            assertThat(result.getErrorCode()).isEqualTo(ErrorCode.INVALID_BRAND_NAME);
        }
    }

    @DisplayName("[P-ADMIN-01] 브랜드 이름은 앞뒤 공백을 빼고 저장하며 대소문자를 구분한다.")
    @Nested
    class NormalizeName {

        @DisplayName("[동등 클래스 분할] 앞뒤 공백을 제거하고 입력한 대소문자는 유지한다.")
        @Test
        void trimsName_andKeepsCase() {
            Brand brand = new Brand("  Nike  ");

            assertThat(brand.getName()).isEqualTo("Nike");
        }
    }

    @DisplayName("[R-ADMIN-13] 삭제된 브랜드는 수정할 수 없다.")
    @Nested
    class RejectUpdateAfterDeletion {

        @DisplayName("[상태 전이] 삭제된 브랜드의 수정을 거절하고 이름을 유지한다.")
        @Test
        void throwsBrandNotFound_andKeepsName() {
            Brand brand = new Brand("브랜드");
            brand.delete();

            CoreException result = assertThrows(CoreException.class, () -> brand.update("수정 브랜드"));

            assertAll(
                () -> assertThat(result.getErrorCode()).isEqualTo(ErrorCode.BRAND_NOT_FOUND),
                () -> assertThat(brand.getName()).isEqualTo("브랜드"),
                () -> assertThat(brand.isDeleted()).isTrue()
            );
        }
    }

    @DisplayName("[R-ADMIN-14] 브랜드를 삭제해도 기존 정보는 함께 삭제되지 않는다.")
    @Nested
    class LogicalDeletion {

        @DisplayName("[상태 전이] 삭제하면 삭제 여부만 바뀌고 이름은 유지된다.")
        @Test
        void keepsName_whenDeleted() {
            Brand brand = new Brand("브랜드");

            brand.delete();

            assertAll(
                () -> assertThat(brand.isDeleted()).isTrue(),
                () -> assertThat(brand.getName()).isEqualTo("브랜드")
            );
        }
    }

    @DisplayName("[P-ADMIN-06] 이미 삭제된 브랜드를 다시 삭제하면 없는 대상으로 거절한다.")
    @Nested
    class RejectRepeatedDeletion {

        @DisplayName("[상태 전이] 재삭제를 거절하고 최초 삭제 상태를 유지한다.")
        @Test
        void throwsBrandNotFound_whenAlreadyDeleted() {
            Brand brand = new Brand("브랜드");
            brand.delete();
            var deletedAt = brand.getDeletedAt();

            CoreException result = assertThrows(CoreException.class, brand::delete);

            assertAll(
                () -> assertThat(result.getErrorCode()).isEqualTo(ErrorCode.BRAND_NOT_FOUND),
                () -> assertThat(brand.getDeletedAt()).isEqualTo(deletedAt)
            );
        }
    }
}
