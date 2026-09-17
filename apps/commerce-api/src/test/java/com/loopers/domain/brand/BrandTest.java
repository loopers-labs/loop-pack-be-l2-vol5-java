package com.loopers.domain.brand;

import com.loopers.domain.error.DomainErrorType;
import com.loopers.domain.error.DomainException;
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

    @DisplayName("브랜드를 생성할 때, ")
    @Nested
    class Create {

        @DisplayName("이름이 1~100자이면, 정상적으로 생성된다. (T-8)")
        @ParameterizedTest
        @ValueSource(ints = {1, 100})
        void createsBrand_whenNameLengthIsWithinRange(int length) {
            // arrange
            String name = "가".repeat(length);

            // act
            Brand brand = new Brand(name);

            // assert
            assertThat(brand.getName()).isEqualTo(name);
        }

        @DisplayName("이름이 없거나 빈칸이면, INVALID_VALUE 예외가 발생한다. (T-8)")
        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   "})
        void throwsInvalidValue_whenNameIsBlank(String name) {
            // act
            DomainException result = assertThrows(DomainException.class, () -> new Brand(name));

            // assert
            assertThat(result.getType()).isEqualTo(DomainErrorType.INVALID_VALUE);
        }

        @DisplayName("이름이 100자를 넘으면, INVALID_VALUE 예외가 발생한다. (T-8)")
        @Test
        void throwsInvalidValue_whenNameExceeds100() {
            // act
            DomainException result = assertThrows(DomainException.class, () -> new Brand("가".repeat(101)));

            // assert
            assertThat(result.getType()).isEqualTo(DomainErrorType.INVALID_VALUE);
        }
    }

    @DisplayName("브랜드 이름을 수정할 때, ")
    @Nested
    class Update {

        @DisplayName("유효한 이름이면, 이름이 바뀐다.")
        @Test
        void updatesName_whenNameIsValid() {
            // arrange
            Brand brand = new Brand("루퍼스");

            // act
            brand.update("새 루퍼스");

            // assert
            assertThat(brand.getName()).isEqualTo("새 루퍼스");
        }

        @DisplayName("유효하지 않은 이름이면, INVALID_VALUE 예외가 발생하고 기존 이름이 유지된다.")
        @Test
        void throwsInvalidValueAndKeepsName_whenNameIsInvalid() {
            // arrange
            Brand brand = new Brand("루퍼스");

            // act
            DomainException result = assertThrows(DomainException.class, () -> brand.update(" "));

            // assert
            assertAll(
                () -> assertThat(result.getType()).isEqualTo(DomainErrorType.INVALID_VALUE),
                () -> assertThat(brand.getName()).isEqualTo("루퍼스")
            );
        }
    }
}
