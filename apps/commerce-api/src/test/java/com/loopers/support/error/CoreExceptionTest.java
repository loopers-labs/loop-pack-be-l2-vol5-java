package com.loopers.support.error;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CoreExceptionTest {
    @DisplayName("ErrorCode 기반의 예외 생성 시, 별도의 메시지가 주어지지 않으면 ErrorCode의 메시지를 사용한다.")
    @Test
    void messageShouldBeErrorCodeMessage_whenCustomMessageIsNull() {
        // arrange
        ErrorCode[] errorCodes = ErrorCode.values();

        // act & assert
        for (ErrorCode errorCode : errorCodes) {
            CoreException exception = new CoreException(errorCode);
            assertThat(exception.getMessage()).isEqualTo(errorCode.getMessage());
        }
    }

    @DisplayName("ErrorCode 기반의 예외 생성 시, 별도의 메시지가 주어지면 해당 메시지를 사용한다.")
    @Test
    void messageShouldBeCustomMessage_whenCustomMessageIsNotNull() {
        // arrange
        String customMessage = "custom message";

        // act
        CoreException exception = new CoreException(ErrorCode.INTERNAL_ERROR, customMessage);

        // assert
        assertThat(exception.getMessage()).isEqualTo(customMessage);
    }
}
