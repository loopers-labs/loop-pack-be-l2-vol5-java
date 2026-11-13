package com.loopers.interfaces.api;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

/** 8주차에 추가한 429·503이 다른 ErrorType과 같은 경로로 상태 코드와 실패 응답이 되는지 본다(docs/week8/decisions.md D-9). */
class ApiControllerAdviceStatusTest {

    private final ApiControllerAdvice advice = new ApiControllerAdvice();

    @DisplayName("TOO_MANY_REQUESTS는 429, SERVICE_UNAVAILABLE은 503이고, 실패 응답의 data는 null, errorCode는 reason phrase다.")
    @Test
    void mapsNewErrorTypesToStatus() {
        ResponseEntity<ApiResponse<?>> tooMany = advice.handle(new CoreException(ErrorType.TOO_MANY_REQUESTS));
        ResponseEntity<ApiResponse<?>> unavailable = advice.handle(new CoreException(ErrorType.SERVICE_UNAVAILABLE));

        assertAll(
            () -> assertThat(tooMany.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS),
            () -> assertThat(tooMany.getBody().meta().result()).isEqualTo(ApiResponse.Metadata.Result.FAIL),
            () -> assertThat(tooMany.getBody().meta().errorCode()).isEqualTo("Too Many Requests"),
            () -> assertThat(tooMany.getBody().data()).isNull(),
            () -> assertThat(unavailable.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE),
            () -> assertThat(unavailable.getBody().meta().errorCode()).isEqualTo("Service Unavailable"),
            () -> assertThat(unavailable.getBody().data()).isNull()
        );
    }
}
