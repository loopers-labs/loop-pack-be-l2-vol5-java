package com.loopers.domain.brand;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BrandTest {

    @DisplayName("Brand를 만들 때,")
    @Nested
    class Create {
        @DisplayName("이름이 1자 이상 100자 이하면 생성한다.")
        @Test
        void createsBrand_whenNameIsValid() {
            // act
            Brand brand = Brand.create("Nike Korea");

            // assert
            assertThat(brand.getName()).isEqualTo("Nike Korea");
        }

        @DisplayName("이름이 공백만으로 구성되면, BAD_REQUEST 예외가 발생한다.")
        @Test
        void throwsException_whenNameIsBlank() {
            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                Brand.create(" ");
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }

        @DisplayName("이름이 100자를 넘으면, BAD_REQUEST 예외가 발생한다.")
        @Test
        void throwsException_whenNameExceedsMaximumLength() {
            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                Brand.create("a".repeat(101));
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }
    }

    @DisplayName("Brand 이름을 변경할 때,")
    @Nested
    class Rename {
        @DisplayName("유효한 이름이면, 이름을 변경한다.")
        @Test
        void changesName_whenNameIsValid() {
            // arrange
            Brand brand = Brand.create("Nike");

            // act
            brand.rename("Nike Korea");

            // assert
            assertThat(brand.getName()).isEqualTo("Nike Korea");
        }
    }
}
