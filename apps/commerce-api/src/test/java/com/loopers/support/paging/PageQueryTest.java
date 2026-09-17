package com.loopers.support.paging;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ER-08 INVALID_PAGE (ASM-20, DR-19). */
class PageQueryTest {

    @DisplayName("[DR-19] page·size 를 생략하면 0, 20 이 기본이다.")
    @Test
    void of_appliesDefaults_whenNull() {
        PageQuery query = PageQuery.of(null, null);

        assertThat(query.page()).isZero();
        assertThat(query.size()).isEqualTo(20);
        assertThat(query.offset()).isZero();
    }

    @DisplayName("[DR-19] size 상한 100 은 허용된다.")
    @Test
    void of_allowsMaxSize() {
        PageQuery query = PageQuery.of(2, 100);

        assertThat(query.offset()).isEqualTo(200L);
    }

    @DisplayName("[ER-08 INVALID_PAGE] page 음수, size 0·음수·101 은 INVALID_PAGE.")
    @ParameterizedTest
    @CsvSource({"-1, 20", "0, 0", "0, -1", "0, 101"})
    void of_throwsInvalidPage_whenOutOfRange(int page, int size) {
        assertThatThrownBy(() -> PageQuery.of(page, size))
            .isInstanceOf(CoreException.class)
            .extracting(e -> ((CoreException) e).getErrorType())
            .isEqualTo(ErrorType.INVALID_PAGE);
    }
}
