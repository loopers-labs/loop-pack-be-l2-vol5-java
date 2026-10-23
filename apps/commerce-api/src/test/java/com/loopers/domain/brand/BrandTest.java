package com.loopers.domain.brand;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BrandTest {

    private static final String NAME_50 = "가".repeat(50);

    // 애노테이션 값에는 repeat() 결과를 쓸 수 없어(컴파일 타임 상수가 아님) 메소드로 제공한다.
    static Stream<String> validNames() {
        return Stream.of("a", NAME_50);
    }

    @DisplayName("브랜드를 생성할 때,")
    @Nested
    class Create {
        @DisplayName("이름의 앞뒤 공백을 제거해 저장한다.")
        @Test
        void trimsName() {
            // act
            Brand brand = Brand.create("  루퍼스  ");

            // assert
            assertThat(brand.getName()).isEqualTo("루퍼스");
        }

        @DisplayName("이름이 1자 이상 50자 이하이면 생성된다.")
        @ParameterizedTest
        @MethodSource("com.loopers.domain.brand.BrandTest#validNames")
        void createsBrand_whenNameLengthIsWithinRange(String name) {
            // act
            Brand brand = Brand.create(name);

            // assert
            assertThat(brand.getName()).isEqualTo(name);
        }

        @DisplayName("이름이 없거나 공백뿐이면 INVALID_INPUT 으로 거절한다.")
        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {" ", "   "})
        void rejectsWithInvalidInput_whenNameIsBlank(String name) {
            // act
            CoreException exception = assertThrows(CoreException.class, () -> Brand.create(name));

            // assert
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.INVALID_INPUT);
        }

        @DisplayName("공백을 제거한 이름이 50자를 넘으면 INVALID_INPUT 으로 거절한다.")
        @Test
        void rejectsWithInvalidInput_whenNameIsLongerThan50() {
            // act
            CoreException exception = assertThrows(CoreException.class, () -> Brand.create(NAME_50 + "나"));

            // assert
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.INVALID_INPUT);
        }

        @DisplayName("앞뒤 공백을 빼면 50자인 이름은 생성된다.")
        @Test
        void createsBrand_whenNameIsWithin50AfterTrim() {
            // act
            Brand brand = Brand.create(" " + NAME_50 + " ");

            // assert
            assertThat(brand.getName()).isEqualTo(NAME_50);
        }
    }

    @DisplayName("브랜드 이름을 수정할 때,")
    @Nested
    class Update {
        @DisplayName("같은 규칙으로 검증해 이름을 바꾼다.")
        @Test
        void changesName_withTrim() {
            // arrange
            Brand brand = Brand.create("루퍼스");

            // act
            brand.update("  새이름 ");

            // assert
            assertThat(brand.getName()).isEqualTo("새이름");
        }

        @DisplayName("유효하지 않은 이름이면 INVALID_INPUT 으로 거절하고, 기존 이름을 유지한다.")
        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   "})
        void rejectsWithInvalidInput_andKeepsName_whenNameIsInvalid(String name) {
            // arrange
            Brand brand = Brand.create("루퍼스");

            // act
            CoreException exception = assertThrows(CoreException.class, () -> brand.update(name));

            // assert
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.INVALID_INPUT);
            assertThat(brand.getName()).isEqualTo("루퍼스");
        }
    }
}
