package com.loopers.interfaces.api;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

class ApiControllerAdviceTest {

    private final ApiControllerAdvice apiControllerAdvice = new ApiControllerAdvice();

    @DisplayName("ADR-12 · CoreException을 응답으로 바꿀 때, ")
    @Nested
    class HandleCoreException {

        @DisplayName("오류 종류마다 정해진 HTTP 상태와 기존 errorCode 문자열로 FAIL 응답을 만든다.")
        @ParameterizedTest(name = "{0} → {1} \"{2}\"")
        @CsvSource({
            "INTERNAL_ERROR, 500, Internal Server Error",
            "BAD_REQUEST,    400, Bad Request",
            "UNAUTHORIZED,   401, Unauthorized",
            "NOT_FOUND,      404, Not Found",
            "CONFLICT,       409, Conflict"
        })
        void mapsErrorTypeToHttpStatusAndErrorCode(ErrorType errorType, int expectedStatus, String expectedErrorCode) {
            // arrange
            CoreException exception = new CoreException(errorType);

            // act
            ResponseEntity<ApiResponse<?>> response = apiControllerAdvice.handle(exception);

            // assert
            assertAll(
                () -> assertThat(response.getStatusCode().value()).isEqualTo(expectedStatus),
                () -> assertThat(response.getBody().meta().result()).isEqualTo(ApiResponse.Metadata.Result.FAIL),
                () -> assertThat(response.getBody().meta().errorCode()).isEqualTo(expectedErrorCode),
                () -> assertThat(response.getBody().meta().message()).isEqualTo(errorType.getMessage()),
                () -> assertThat(response.getBody().data()).isNull()
            );
        }
    }
}
