package com.loopers.interfaces.api;

import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.assertj.core.api.Assertions.assertThat;

class ErrorTypeHttpStatusMapperTest {

    @DisplayName("도메인 에러 종류를 HTTP 상태로 변환할 때,")
    @Nested
    class ToHttpStatus {
        @DisplayName("각 에러 종류에 약속된 HTTP 상태를 반환한다.")
        @Test
        void returnsMappedHttpStatus_whenErrorTypeIsProvided() {
            assertAll(
                () -> assertThat(ErrorTypeHttpStatusMapper.toHttpStatus(ErrorType.INVALID_INPUT)).isEqualTo(HttpStatus.BAD_REQUEST),
                () -> assertThat(ErrorTypeHttpStatusMapper.toHttpStatus(ErrorType.NOT_FOUND)).isEqualTo(HttpStatus.NOT_FOUND),
                () -> assertThat(ErrorTypeHttpStatusMapper.toHttpStatus(ErrorType.CONFLICT)).isEqualTo(HttpStatus.CONFLICT),
                () -> assertThat(ErrorTypeHttpStatusMapper.toHttpStatus(ErrorType.INTERNAL_ERROR)).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR)
            );
        }

        @DisplayName("모든 에러 종류가 빠짐없이 매핑되어 있다.")
        @Test
        void returnsNonNullHttpStatus_forEveryErrorType() {
            // exhaustive switch 가 컴파일 타임에 보장하지만, default 절이 추가되어
            // 안전장치가 무력화되는 경우를 잡기 위한 테스트
            for (ErrorType errorType : ErrorType.values()) {
                assertThat(ErrorTypeHttpStatusMapper.toHttpStatus(errorType))
                    .as("ErrorType.%s 의 HTTP 상태 매핑", errorType.name())
                    .isNotNull();
            }
        }
    }
}
