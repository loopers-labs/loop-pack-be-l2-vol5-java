package com.loopers.domain.catalog;

import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static com.loopers.support.ErrorAssertions.assertThrowsErrorType;
import static org.assertj.core.api.Assertions.assertThat;

/** FR-PRODUCT-01 정렬 (ASM-08). ER-07 INVALID_SORT. */
class ProductSortTest {

    @DisplayName("[ASM-08] 생략하면 latest.")
    @ParameterizedTest
    @NullAndEmptySource
    void from_returnsLatest_whenAbsent(String value) {
        assertThat(ProductSort.from(value)).isEqualTo(ProductSort.LATEST);
    }

    @DisplayName("[ASM-08] 허용 목록의 값은 각각 매핑된다.")
    @Test
    void from_mapsAllowedValues() {
        assertThat(ProductSort.from("latest")).isEqualTo(ProductSort.LATEST);
        assertThat(ProductSort.from("price_asc")).isEqualTo(ProductSort.PRICE_ASC);
        assertThat(ProductSort.from("likes_desc")).isEqualTo(ProductSort.LIKES_DESC);
    }

    @DisplayName("[ER-07 INVALID_SORT] 허용 목록 밖·둘 이상 지정(쉼표 결합)은 거부된다.")
    @ParameterizedTest
    @ValueSource(strings = {"price_desc", "LATEST", "latest,price_asc"})
    void from_throwsInvalidSort_whenNotAllowed(String value) {
        assertThrowsErrorType(() -> ProductSort.from(value), ErrorType.INVALID_SORT);
    }
}
