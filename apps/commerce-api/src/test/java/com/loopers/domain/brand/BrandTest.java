package com.loopers.domain.brand;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BrandTest {
    @DisplayName("브랜드를 생성할 때, ")
    @Nested
    class Create {
        @DisplayName("이름이 주어지면, 정상적으로 생성된다.")
        @Test
        void createsBrand_whenNameIsProvided() {
            // act
            Brand brand = new Brand("루퍼스");

            // assert
            assertThat(brand.getName()).isEqualTo("루퍼스");
        }

        @DisplayName("이름이 null 이면, BAD_REQUEST 예외가 발생한다.")
        @Test
        void throwsBadRequestException_whenNameIsNull() {
            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                new Brand(null);
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }

        @DisplayName("이름이 빈 문자열이면, BAD_REQUEST 예외가 발생한다.")
        @Test
        void throwsBadRequestException_whenNameIsEmpty() {
            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                new Brand("");
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }

        @DisplayName("이름이 공백뿐이면, BAD_REQUEST 예외가 발생한다.")
        @Test
        void throwsBadRequestException_whenNameIsBlank() {
            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                new Brand("   ");
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }

        @DisplayName("100자 이름이면 브랜드가 생성된다")
        @Test
        void createsBrand_whenNameLengthIsMax() {
            // arrange
            String name = "가".repeat(100);

            // act
            Brand brand = new Brand(name);

            // assert
            assertThat(brand.getName()).hasSize(100);
        }

        @DisplayName("101자 이름이면 브랜드 생성을 거절한다")
        @Test
        void throwsBadRequestException_whenNameLengthExceedsMax() {
            // arrange
            String name = "가".repeat(101);

            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                new Brand(name);
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }

        @DisplayName("이름으로 생성한 브랜드는 저장 전 ID가 없고 삭제되지 않은 상태다")
        @Test
        void hasNoIdAndIsNotDeleted_whenCreated() {
            // act
            Brand brand = new Brand("루퍼스");

            // assert
            assertThat(brand.getId()).isZero();
            assertThat(brand.getDeletedAt()).isNull();
        }
    }

    @DisplayName("브랜드 이름을 수정할 때, ")
    @Nested
    class ChangeName {
        @DisplayName("유효한 이름이 주어지면, 해당 이름으로 변경된다.")
        @Test
        void changesName_whenNameIsValid() {
            // arrange
            Brand brand = new Brand("루퍼스");

            // act
            brand.changeName("새 이름");

            // assert
            assertThat(brand.getName()).isEqualTo("새 이름");
        }

        @DisplayName("이름이 비어있으면, BAD_REQUEST 예외가 발생하고 기존 이름이 유지된다.")
        @Test
        void keepsName_whenNameIsBlank() {
            // arrange
            Brand brand = new Brand("루퍼스");

            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                brand.changeName("   ");
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
            assertThat(brand.getName()).isEqualTo("루퍼스");
        }

        @DisplayName("이름이 100자를 넘으면, BAD_REQUEST 예외가 발생하고 기존 이름이 유지된다.")
        @Test
        void keepsName_whenNameLengthExceedsMax() {
            // arrange
            Brand brand = new Brand("루퍼스");

            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                brand.changeName("가".repeat(101));
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
            assertThat(brand.getName()).isEqualTo("루퍼스");
        }
    }
}
