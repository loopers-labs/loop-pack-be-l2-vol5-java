package com.loopers.domain.brand;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

@DisplayName("Brand 는 브랜드 정보와 삭제 가능 조건을 책임진다.")
class BrandModelTest {

    @DisplayName("등록")
    @Nested
    class Create {
        @DisplayName("이름의 앞뒤 공백을 제거해 저장하고 삭제되지 않은 상태로 시작한다.")
        @Test
        void createsActiveBrand() {
            BrandModel brand = BrandModel.create("  나이키  ");

            assertAll(
                () -> assertThat(brand.getName()).isEqualTo("나이키"),
                () -> assertThat(brand.isDeleted()).isFalse()
            );
        }

        @DisplayName("이름이 비어 있으면 INVALID_BRAND_NAME 으로 거절한다.")
        @ParameterizedTest
        @NullSource
        @ValueSource(strings = {"", "   "})
        void rejectsBlankName(String name) {
            assertThatThrownBy(() -> BrandModel.create(name))
                .isInstanceOf(CoreException.class)
                .extracting("errorType")
                .isEqualTo(ErrorType.INVALID_BRAND_NAME);
        }

        @DisplayName("이름이 100자를 넘으면 거절하고 100자는 허용한다.")
        @Test
        void checksNameLengthBoundary() {
            String maxLength = "가".repeat(100);

            assertThat(BrandModel.create(maxLength).getName()).isEqualTo(maxLength);
            assertThatThrownBy(() -> BrandModel.create("가".repeat(101)))
                .isInstanceOf(CoreException.class)
                .extracting("errorType")
                .isEqualTo(ErrorType.INVALID_BRAND_NAME);
        }
    }

    @DisplayName("수정")
    @Nested
    class UpdateName {
        @DisplayName("이름을 바꾼다.")
        @Test
        void updatesName() {
            BrandModel brand = BrandModel.create("나이키");

            brand.updateName("아디다스");

            assertThat(brand.getName()).isEqualTo("아디다스");
        }

        @DisplayName("잘못된 이름으로 수정하면 거절하고 기존 이름을 유지한다.")
        @Test
        void keepsNameOnInvalidInput() {
            BrandModel brand = BrandModel.create("나이키");

            assertThatThrownBy(() -> brand.updateName("  "))
                .isInstanceOf(CoreException.class)
                .extracting("errorType")
                .isEqualTo(ErrorType.INVALID_BRAND_NAME);
            assertThat(brand.getName()).isEqualTo("나이키");
        }
    }

    @DisplayName("삭제")
    @Nested
    class Delete {
        @DisplayName("연결된 활성 상품이 없으면 삭제한다.")
        @Test
        void deletesWhenNoActiveProduct() {
            BrandModel brand = BrandModel.create("나이키");

            brand.delete(false);

            assertThat(brand.isDeleted()).isTrue();
        }

        @DisplayName("삭제되지 않은 상품이 하나라도 있으면 재고와 무관하게 BRAND_HAS_ACTIVE_PRODUCTS 로 거절한다.")
        @Test
        void rejectsWhenActiveProductExists() {
            BrandModel brand = BrandModel.create("나이키");

            assertThatThrownBy(() -> brand.delete(true))
                .isInstanceOf(CoreException.class)
                .extracting("errorType")
                .isEqualTo(ErrorType.BRAND_HAS_ACTIVE_PRODUCTS);
            assertThat(brand.isDeleted()).isFalse();
        }
    }
}
