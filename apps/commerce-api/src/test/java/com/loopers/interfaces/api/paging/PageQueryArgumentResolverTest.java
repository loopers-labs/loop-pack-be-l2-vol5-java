package com.loopers.interfaces.api.paging;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ER-08 INVALID_PAGE — 쿼리 파라미터 형식 (DR-19). 범위 검증은 PageQueryTest. */
class PageQueryArgumentResolverTest {

    @DisplayName("[DR-19] 누락·공백은 null 로 넘겨 기본값을 쓰게 한다.")
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" "})
    void parse_returnsNull_whenMissing(String value) {
        assertThat(PageQueryArgumentResolver.parse("page", value)).isNull();
    }

    @DisplayName("[DR-19] 정수 문자열은 앞뒤 공백을 무시하고 파싱한다.")
    @Test
    void parse_returnsInteger() {
        assertThat(PageQueryArgumentResolver.parse("page", " 3 ")).isEqualTo(3);
    }

    @DisplayName("[ER-08 INVALID_PAGE] 정수가 아니면 INVALID_PAGE.")
    @ParameterizedTest
    @ValueSource(strings = {"abc", "1.5", "1e3"})
    void parse_throwsInvalidPage_whenNotInteger(String value) {
        assertThatThrownBy(() -> PageQueryArgumentResolver.parse("size", value))
            .isInstanceOf(CoreException.class)
            .extracting(e -> ((CoreException) e).getErrorType())
            .isEqualTo(ErrorType.INVALID_PAGE);
    }
}
