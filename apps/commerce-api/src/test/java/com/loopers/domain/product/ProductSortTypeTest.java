package com.loopers.domain.product;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProductSortTypeTest {
    @DisplayName("정렬 조건을 해석할 때, ")
    @Nested
    class From {
        @DisplayName("지원하는 정렬값이면, 해당 정렬 조건으로 해석된다.")
        @Test
        void returnsSortType_whenValueIsSupported() {
            // assert
            assertThat(ProductSortType.from("latest")).isEqualTo(ProductSortType.LATEST);
            assertThat(ProductSortType.from("price_asc")).isEqualTo(ProductSortType.PRICE_ASC);
            assertThat(ProductSortType.from("likes_desc")).isEqualTo(ProductSortType.LIKES_DESC);
        }

        @DisplayName("정렬값이 주어지지 않으면, 최신순으로 해석된다.")
        @Test
        void returnsLatest_whenValueIsAbsent() {
            // assert
            assertThat(ProductSortType.from(null)).isEqualTo(ProductSortType.LATEST);
        }

        @DisplayName("지원하지 않는 정렬값이면, BAD_REQUEST 예외가 발생한다.")
        @Test
        void throwsBadRequestException_whenValueIsNotSupported() {
            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                ProductSortType.from("price_desc");
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }
    }
}
