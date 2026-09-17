package com.loopers.domain.common;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

@DisplayName("PageCommand 와 ListSort 는 목록 조회의 잠정 페이지·정렬 정책을 책임진다.")
class PageCommandTest {

    @DisplayName("페이지 입력")
    @Nested
    class Page {
        @DisplayName("생략하면 page=0, size=20 을 사용한다.")
        @Test
        void usesDefaults() {
            PageCommand command = PageCommand.of(null, null);

            assertAll(
                () -> assertThat(command.page()).isZero(),
                () -> assertThat(command.size()).isEqualTo(20)
            );
        }

        @DisplayName("허용 범위의 값은 그대로 사용한다.")
        @ParameterizedTest
        @CsvSource({"0, 1", "3, 20", "0, 100"})
        void acceptsValidRange(int page, int size) {
            PageCommand command = PageCommand.of(page, size);

            assertAll(
                () -> assertThat(command.page()).isEqualTo(page),
                () -> assertThat(command.size()).isEqualTo(size)
            );
        }

        @DisplayName("page 가 음수이거나 size 가 1~100 을 벗어나면 INVALID_PAGE_REQUEST 로 거절한다.")
        @ParameterizedTest
        @CsvSource({"-1, 20", "0, 0", "0, 101"})
        void rejectsOutOfRange(int page, int size) {
            assertThatThrownBy(() -> PageCommand.of(page, size))
                .isInstanceOf(CoreException.class)
                .extracting("errorType")
                .isEqualTo(ErrorType.INVALID_PAGE_REQUEST);
        }
    }

    @DisplayName("정렬 입력")
    @Nested
    class Sort {
        @DisplayName("생략하면 latest 를 사용한다.")
        @Test
        void usesLatestByDefault() {
            assertThat(ListSort.from(null)).isEqualTo(ListSort.LATEST);
        }

        @DisplayName("latest 와 oldest 를 지원한다.")
        @Test
        void supportsLatestAndOldest() {
            assertAll(
                () -> assertThat(ListSort.from("latest")).isEqualTo(ListSort.LATEST),
                () -> assertThat(ListSort.from("oldest")).isEqualTo(ListSort.OLDEST)
            );
        }

        @DisplayName("지원하지 않는 값은 INVALID_SORT 로 거절한다.")
        @Test
        void rejectsUnsupportedValue() {
            assertThatThrownBy(() -> ListSort.from("price_asc"))
                .isInstanceOf(CoreException.class)
                .extracting("errorType")
                .isEqualTo(ErrorType.INVALID_SORT);
        }
    }

    @DisplayName("페이지 결과")
    @Nested
    class Result {
        @DisplayName("전체 자원 수로 전체 페이지 수를 올림 계산한다.")
        @Test
        void calculatesTotalPages() {
            PageResult<String> result = PageResult.of(List.of("a", "b"), PageCommand.of(0, 2), 5L);

            assertAll(
                () -> assertThat(result.items()).containsExactly("a", "b"),
                () -> assertThat(result.page()).isZero(),
                () -> assertThat(result.size()).isEqualTo(2),
                () -> assertThat(result.totalElements()).isEqualTo(5L),
                () -> assertThat(result.totalPages()).isEqualTo(3)
            );
        }

        @DisplayName("결과가 없으면 전체 페이지 수는 0 이다.")
        @Test
        void returnsZeroTotalPages() {
            PageResult<String> result = PageResult.of(List.of(), PageCommand.of(0, 20), 0L);

            assertAll(
                () -> assertThat(result.items()).isEmpty(),
                () -> assertThat(result.totalElements()).isZero(),
                () -> assertThat(result.totalPages()).isZero()
            );
        }
    }
}
