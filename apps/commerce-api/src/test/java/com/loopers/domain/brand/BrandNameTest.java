package com.loopers.domain.brand;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BrandNameTest {

    @DisplayName("BRAND-NAME-01: 앞뒤 공백만 제거하고 내부 공백과 대소문자는 유지한다.")
    @Test
    void removesSurroundingWhitespace() {
        BrandName name = new BrandName(" \tLoopers  Brand\n ");

        assertThat(name.value()).isEqualTo("Loopers  Brand");
    }

    @DisplayName("BRAND-NAME-02: null 또는 공백 제거 후 빈 이름은 거절한다.")
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t", "\n", " \t\n"})
    @ParameterizedTest
    void rejectsEmptyName(String value) {
        assertNameRejected(value, BrandNameException.Reason.EMPTY_NAME);
    }

    @DisplayName("BRAND-NAME-03: 공백 제거 후 코드 포인트 1~100자인 이름을 허용한다.")
    @CsvSource({"가, 1", "가, 100", "😀, 1", "😀, 100"})
    @ParameterizedTest
    void allowsNameWithinCodePointLimit(String character, int length) {
        String value = character.repeat(length);

        BrandName name = new BrandName(" " + value + " ");

        assertThat(name.value()).isEqualTo(value);
    }

    @DisplayName("BRAND-NAME-04: 코드 포인트 101자인 이름을 거절한다.")
    @ValueSource(strings = {"가", "😀"})
    @ParameterizedTest
    void rejectsNameExceedingCodePointLimit(String character) {
        String value = " " + character.repeat(101) + " ";

        assertNameRejected(value, BrandNameException.Reason.NAME_TOO_LONG);
    }

    private void assertNameRejected(String value, BrandNameException.Reason reason) {
        assertThatThrownBy(() -> new BrandName(value))
            .isInstanceOfSatisfying(BrandNameException.class,
                error -> assertThat(error.getReason()).isEqualTo(reason));
    }
}
