package com.loopers.domain.brand;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BrandModelTest {

    @DisplayName("브랜드를 생성할 때, ")
    @Nested
    class Create {
        @DisplayName("이름이 주어지면, 정상적으로 생성된다.")
        @Test
        void createsBrand_whenNameIsProvided() {
            // arrange
            String name = "나이키";

            // act
            BrandModel brand = new BrandModel(name);

            // assert
            assertThat(brand.getName()).isEqualTo(name);
        }

        @DisplayName("이름이 공백이면, BAD_REQUEST 예외가 발생한다.")
        @Test
        void throwsBadRequestException_whenNameIsBlank() {
            // act
            CoreException result = assertThrows(CoreException.class, () -> new BrandModel("   "));

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }
    }
}
