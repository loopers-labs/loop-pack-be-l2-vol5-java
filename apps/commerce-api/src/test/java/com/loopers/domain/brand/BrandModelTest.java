package com.loopers.domain.brand;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BrandModelTest {

    @DisplayName("브랜드를 만들 때, ")
    @Nested
    class Create {

        @DisplayName("BRD-01 이름의 앞뒤 공백을 제거해 저장한다.")
        @Test
        void trimsName() {
            // act
            BrandModel brand = new BrandModel("  나이키  ", "스포츠 브랜드");

            // assert
            assertThat(brand.getName()).isEqualTo("나이키");
            assertThat(brand.getDescription()).isEqualTo("스포츠 브랜드");
        }

        @DisplayName("BRD-01 이름 50자와 설명 200자는 허용하고, 설명은 비워 둘 수 있다.")
        @Test
        void acceptsBoundaryLengths() {
            // act
            BrandModel withMaxLengths = new BrandModel("가".repeat(50), "나".repeat(200));
            BrandModel withoutDescription = new BrandModel("나이키", null);

            // assert
            assertThat(withMaxLengths.getName()).hasSize(50);
            assertThat(withoutDescription.getDescription()).isNull();
        }

        @DisplayName("BRD-01 공백뿐인 이름은 거절한다.")
        @Test
        void rejectsBlankName() {
            // act & assert
            assertThatThrownBy(() -> new BrandModel("   ", null))
                .isInstanceOf(CoreException.class)
                .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST);
        }

        @DisplayName("BRD-01 이름이 없으면 거절한다.")
        @Test
        void rejectsNullName() {
            // act & assert
            assertThatThrownBy(() -> new BrandModel(null, null))
                .isInstanceOf(CoreException.class)
                .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST);
        }

        @DisplayName("BRD-01 51자 이름은 거절한다.")
        @Test
        void rejectsTooLongName() {
            // act & assert
            assertThatThrownBy(() -> new BrandModel("가".repeat(51), null))
                .isInstanceOf(CoreException.class)
                .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST);
        }

        @DisplayName("BRD-01 201자 설명은 거절한다.")
        @Test
        void rejectsTooLongDescription() {
            // act & assert
            assertThatThrownBy(() -> new BrandModel("나이키", "나".repeat(201)))
                .isInstanceOf(CoreException.class)
                .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST);
        }
    }

    @DisplayName("브랜드를 수정할 때, ")
    @Nested
    class Update {

        @DisplayName("BRD-01 유효한 이름·설명으로 바꾼다.")
        @Test
        void updatesNameAndDescription() {
            // arrange
            BrandModel brand = new BrandModel("나이키", "스포츠 브랜드");

            // act
            brand.update(" 아디다스 ", null);

            // assert
            assertThat(brand.getName()).isEqualTo("아디다스");
            assertThat(brand.getDescription()).isNull();
        }

        @DisplayName("BRD-01 유효하지 않은 이름이면 거절하고, 이름·설명은 그대로다.")
        @Test
        void rejectsInvalidNameAndKeepsPreviousValues() {
            // arrange
            BrandModel brand = new BrandModel("나이키", "스포츠 브랜드");

            // act & assert
            assertThatThrownBy(() -> brand.update("   ", "바뀐 설명"))
                .isInstanceOf(CoreException.class)
                .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST);
            assertThat(brand.getName()).isEqualTo("나이키");
            assertThat(brand.getDescription()).isEqualTo("스포츠 브랜드");
        }
    }
}
