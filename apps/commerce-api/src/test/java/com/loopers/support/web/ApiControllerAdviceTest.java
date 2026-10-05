package com.loopers.support.web;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import jakarta.persistence.OptimisticLockException;
import org.springframework.http.ResponseEntity;

import java.sql.SQLException;

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
            "CONFLICT,       409, Conflict",
            "CONCURRENCY_CONFLICT, 409, Concurrency Conflict"
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

    @DisplayName("ADR-W3-04 · 잠금 실패를 응답으로 바꿀 때, ")
    @Nested
    class HandleLockFailure {

        @DisplayName("교착(MySQL 1213)은 Hibernate가 잠금 조회에서 OptimisticLockException으로 감싸 올려도 500이 아니라 409 Concurrency Conflict다.")
        @Test
        void mapsDeadlockWrappedAsOptimisticLock() {
            // arrange: Hibernate 6.6 ExceptionConverterImpl.wrapLockException의 마지막 분기가 만드는 모양
            SQLException deadlock = new SQLException("Deadlock found when trying to get lock", "40001", 1213);
            OptimisticLockException wrapped = new OptimisticLockException(new RuntimeException("could not execute statement", deadlock));

            // act
            ResponseEntity<ApiResponse<?>> response = apiControllerAdvice.handle(wrapped);

            // assert
            assertAll(
                () -> assertThat(response.getStatusCode().value()).isEqualTo(409),
                () -> assertThat(response.getBody().meta().errorCode()).isEqualTo("Concurrency Conflict")
            );
        }

        @DisplayName("잠금과 무관한 예외는 그대로 500이다.")
        @Test
        void keepsInternalErrorForOtherExceptions() {
            // act
            ResponseEntity<ApiResponse<?>> response = apiControllerAdvice.handle(new IllegalStateException("다른 오류"));

            // assert
            assertThat(response.getStatusCode().value()).isEqualTo(500);
        }
    }
}
