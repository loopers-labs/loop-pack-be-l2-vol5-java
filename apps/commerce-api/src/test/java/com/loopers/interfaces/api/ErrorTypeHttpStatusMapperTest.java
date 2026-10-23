package com.loopers.interfaces.api;

import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorTypeHttpStatusMapperTest {

    @DisplayName("도메인 에러 종류를 HTTP 상태로 변환할 때,")
    @Nested
    class ToHttpStatus {
        @DisplayName("모든 에러 종류가 빠짐없이 매핑되어 있다.")
        @Test
        void returnsNonNullHttpStatus_forEveryErrorType() {
            // exhaustive switch 가 컴파일 타임에 보장하지만, default 절이 추가되어
            // 안전장치가 무력화되는 경우를 잡기 위한 테스트.
            // 상수별 HTTP 상태는 해당 에러를 던지는 API 의 E2E 테스트에서 확인한다.
            for (ErrorType errorType : ErrorType.values()) {
                assertThat(ErrorTypeHttpStatusMapper.toHttpStatus(errorType))
                    .as("ErrorType.%s 의 HTTP 상태 매핑", errorType.name())
                    .isNotNull();
            }
        }

        @DisplayName("결제 시도 충돌은 409 로 변환한다.")
        @Test
        void returnsConflict_forPaymentAttemptConflict() {
            assertThat(ErrorTypeHttpStatusMapper.toHttpStatus(ErrorType.PAYMENT_ATTEMPT_CONFLICT))
                .isEqualTo(HttpStatus.CONFLICT);
        }
    }
}
