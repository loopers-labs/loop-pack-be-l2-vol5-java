package com.loopers.domain.brand;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

class BrandTest {

    @DisplayName("BRAND-01: 브랜드 생성 시 정리된 유효한 이름을 보관한다.")
    @Test
    void createsBrandWithNormalizedName() {
        Brand brand = new Brand("  Loopers  Brand  ");

        assertThat(brand.getName()).isEqualTo("Loopers  Brand");
    }

    @DisplayName("BRAND-02: 잘못된 이름으로 브랜드를 생성할 수 없다.")
    @ParameterizedTest
    @MethodSource("invalidNames")
    void rejectsCreationWithInvalidName(String name, BrandNameException.Reason reason) {
        assertNameRejected(() -> new Brand(name), reason);
    }

    @DisplayName("BRAND-03: 브랜드 이름을 정리된 새 이름으로 변경한다.")
    @Test
    void renamesBrand() {
        Brand brand = new Brand("Original Brand");

        brand.rename(" \tNew  Brand\n ");

        assertThat(brand.getName()).isEqualTo("New  Brand");
    }

    @DisplayName("BRAND-04: 잘못된 이름 변경은 거절하고 기존 이름을 보존한다.")
    @ParameterizedTest
    @MethodSource("invalidNames")
    void keepsOriginalNameWhenRenameFails(String name, BrandNameException.Reason reason) {
        Brand brand = new Brand("Original Brand");

        assertAll(
            () -> assertNameRejected(() -> brand.rename(name), reason),
            () -> assertThat(brand.getName()).isEqualTo("Original Brand")
        );
    }

    @DisplayName("BRAND-05: 생성과 변경 모두 코드 포인트 100자인 이름을 허용한다.")
    @ParameterizedTest
    @ValueSource(strings = {"가", "😀"})
    void allowsMaximumLengthNameForCreationAndRename(String character) {
        String name = character.repeat(100);
        Brand created = new Brand(" " + name + " ");
        Brand renamed = new Brand("Original Brand");

        renamed.rename(" " + name + " ");

        assertAll(
            () -> assertThat(created.getName()).isEqualTo(name),
            () -> assertThat(renamed.getName()).isEqualTo(name)
        );
    }

    @DisplayName("BRAND-06: 이름 변경 실패 후에도 유효한 이름으로 다시 변경할 수 있다.")
    @Test
    void renamesAfterRejectedChange() {
        Brand brand = new Brand("Original Brand");
        assertNameRejected(() -> brand.rename(" "), BrandNameException.Reason.EMPTY_NAME);

        brand.rename("Valid Brand");

        assertThat(brand.getName()).isEqualTo("Valid Brand");
    }

    private void assertNameRejected(ThrowingCallable action, BrandNameException.Reason reason) {
        assertThatThrownBy(action)
            .isInstanceOfSatisfying(BrandNameException.class,
                error -> assertThat(error.getReason()).isEqualTo(reason));
    }

    private static Stream<Arguments> invalidNames() {
        return Stream.of(
            Arguments.of(null, BrandNameException.Reason.EMPTY_NAME),
            Arguments.of(" \t\n ", BrandNameException.Reason.EMPTY_NAME),
            Arguments.of("가".repeat(101), BrandNameException.Reason.NAME_TOO_LONG),
            Arguments.of("😀".repeat(101), BrandNameException.Reason.NAME_TOO_LONG)
        );
    }
}
