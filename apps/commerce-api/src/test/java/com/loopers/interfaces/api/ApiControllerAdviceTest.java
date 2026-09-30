package com.loopers.interfaces.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

import com.loopers.application.support.error.ApplicationErrorCode;
import com.loopers.application.support.error.ApplicationException;
import com.loopers.domain.support.error.DomainErrorCode;
import com.loopers.domain.support.error.DomainException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class ApiControllerAdviceTest {
    private final ApiControllerAdvice advice = new ApiControllerAdvice(new ApiErrorMapper());

    @DisplayName("잘못된 사용자 ID 도메인 오류를 400 응답으로 변환한다")
    @Test
    void mapsInvalidUserIdToBadRequest() {
        DomainException exception = new DomainException(DomainErrorCode.INVALID_USER_ID);

        ResponseEntity<ApiResponse<?>> response = advice.handle(exception);

        assertFailure(response, HttpStatus.BAD_REQUEST, "Bad Request", exception.getMessage());
    }

    @DisplayName("사용자 없음 애플리케이션 오류를 404 응답으로 변환한다")
    @Test
    void mapsUserNotFoundToNotFound() {
        ApplicationException exception = new ApplicationException(ApplicationErrorCode.USER_NOT_FOUND);

        ResponseEntity<ApiResponse<?>> response = advice.handle(exception);

        assertFailure(response, HttpStatus.NOT_FOUND, "Not Found", exception.getMessage());
    }

    @DisplayName("예상치 못한 예외를 500 응답으로 변환한다")
    @Test
    void mapsUnexpectedExceptionToInternalServerError() {
        RuntimeException exception = new RuntimeException("boom");

        ResponseEntity<ApiResponse<?>> response = advice.handle(exception);

        assertFailure(response, HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error", "일시적인 오류가 발생했습니다.");
    }

    private void assertFailure(
        ResponseEntity<ApiResponse<?>> response,
        HttpStatus status,
        String errorCode,
        String message
    ) {
        ApiResponse<?> body = response.getBody();
        assertThat(body).isNotNull();
        assertAll(
            () -> assertThat(response.getStatusCode()).isEqualTo(status),
            () -> assertThat(body.meta().result()).isEqualTo(ApiResponse.Metadata.Result.FAIL),
            () -> assertThat(body.meta().errorCode()).isEqualTo(errorCode),
            () -> assertThat(body.meta().message()).isEqualTo(message),
            () -> assertThat(body.data()).isNull()
        );
    }
}
